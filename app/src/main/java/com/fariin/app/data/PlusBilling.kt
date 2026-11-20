package com.fariin.app.data

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * FARIIN PLUS subscriptions on Android (Google Play Billing; owner 2026-11-19: Play only, no other
 * way to pay). One subscription product `fariin_plus` with base plans `monthly` and `yearly`.
 *
 * Offered only while plusState says purchases "on" for this account. Before a purchase the server
 * gives a token (`plusPurchaseToken`) that rides as obfuscatedAccountId, so it can tie the purchase to
 * this account. Every purchase, the new one and each one Play still lists at app start, goes to
 * `plusPlayVerify({purchaseToken})`; Plus is never turned on here, the app reads plusState again. The
 * app does NOT acknowledge: the server does (within Play's 3 days). OTHER_ACCOUNT shows the server's
 * words; not-found stays silent (Plus is not shown to this account).
 */
object PlusBilling {
    const val PRODUCT = "fariin_plus"
    private val fns get() = FirebaseFunctions.getInstance(PlusStore.REGION)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    class Plan(val basePlanId: String, val offerToken: String, val price: String, val micros: Long, val period: String)

    var plans by mutableStateOf<List<Plan>>(emptyList()); private set
    var loaded by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    /** A line for the page's alert ("Your purchase is waiting for approval ..."). */
    var notice by mutableStateOf<String?>(null)

    private var client: BillingClient? = null
    private var details: ProductDetails? = null
    private var appCtx: Context? = null
    private var checkedThisRun = false

    private val listener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases?.forEach { handle(it, fromBuy = true) } ?: run { busy = false }
            BillingClient.BillingResponseCode.USER_CANCELED -> busy = false
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> { busy = false; restoreQuietly() }
            else -> { busy = false; notice = "The purchase did not go through. Try again." }
        }
    }

    private fun client(c: Context): BillingClient {
        appCtx = c.applicationContext
        return client ?: BillingClient.newBuilder(c.applicationContext)
            .setListener(listener)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build().also { client = it }
    }

    private suspend fun ready(c: Context): BillingClient? {
        val bc = client(c)
        if (bc.isReady) return bc
        val done = CompletableDeferred<Boolean>()
        bc.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) { done.complete(r.responseCode == BillingClient.BillingResponseCode.OK) }
            override fun onBillingServiceDisconnected() { if (!done.isCompleted) done.complete(false) }
        })
        return if (withTimeoutOrNull(10_000) { done.await() } == true) bc else null
    }

    /** The plans with Play's own prices; only when the server opened purchases to this account. */
    fun loadPlans(c: Context) {
        if (!PlusStore.purchasesOn) return
        scope.launch {
            val bc = ready(c)
            val r = bc?.queryProductDetails(QueryProductDetailsParams.newBuilder().setProductList(listOf(
                QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT).setProductType(BillingClient.ProductType.SUBS).build(),
            )).build())
            val d = r?.productDetailsList?.firstOrNull()
            details = d
            plans = d?.subscriptionOfferDetails.orEmpty()
                // The base plans themselves (no introductory offers chosen for the person here).
                .filter { it.offerId == null && it.basePlanId in setOf("monthly", "yearly") }
                .mapNotNull { o ->
                    val phase = o.pricingPhases.pricingPhaseList.lastOrNull() ?: return@mapNotNull null
                    Plan(o.basePlanId, o.offerToken, phase.formattedPrice, phase.priceAmountMicros, phase.billingPeriod)
                }
                .sortedBy { if (it.basePlanId == "yearly") 0 else 1 }
            loaded = true
        }
    }

    /** Opens Play's purchase sheet for [plan]. */
    fun buy(activity: Activity, plan: Plan) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val token = (fns.getHttpsCallable("plusPurchaseToken").call(emptyMap<String, Any>()).await().getData() as? Map<*, *>)
                    ?.get("token") as? String
                val d = details
                val bc = ready(activity)
                if (token == null || d == null || bc == null) { busy = false; notice = "Could not start the purchase. Check your connection and try again."; return@launch }
                val params = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(d).setOfferToken(plan.offerToken).build()))
                    .setObfuscatedAccountId(token)
                    .build()
                val r = bc.launchBillingFlow(activity, params)
                if (r.responseCode != BillingClient.BillingResponseCode.OK) { busy = false; notice = "Could not start the purchase. Check your connection and try again." }
            } catch (e: FirebaseFunctionsException) {
                busy = false
                if (e.code != FirebaseFunctionsException.Code.NOT_FOUND) notice = "Could not start the purchase. Check your connection and try again."
            } catch (_: Exception) { busy = false; notice = "Could not start the purchase. Check your connection and try again." }
        }
    }

    /** Each app start (once a run, while Plus is visible): every subscription Play still lists goes to the server. */
    fun checkOwned(c: Context) {
        if (checkedThisRun || !PlusStore.visible) return
        checkedThisRun = true
        scope.launch {
            val bc = ready(c) ?: run { checkedThisRun = false; return@launch }
            val r = bc.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build())
            r.purchasesList.forEach { handle(it, fromBuy = false) }
        }
    }

    private fun restoreQuietly() { appCtx?.let { checkedThisRun = false; checkOwned(it) } }

    private fun handle(p: Purchase, fromBuy: Boolean) {
        if (p.purchaseState == Purchase.PurchaseState.PENDING) {
            busy = false
            if (fromBuy) notice = "Your purchase is waiting for approval. Fariin Plus turns on once it is approved."
            return
        }
        if (p.purchaseState != Purchase.PurchaseState.PURCHASED) { busy = false; return }
        scope.launch {
            try {
                fns.getHttpsCallable("plusPlayVerify").call(mapOf("purchaseToken" to p.purchaseToken)).await()
                PlusStore.refresh()
            } catch (e: FirebaseFunctionsException) {
                when {
                    e.code == FirebaseFunctionsException.Code.NOT_FOUND -> {}
                    PlusStore.detailsCode(e) == "OTHER_ACCOUNT" -> notice = e.message
                    fromBuy -> notice = "Fariin could not confirm your subscription right now. Try again in a moment."
                }
            } catch (_: Exception) {
                if (fromBuy) notice = "Fariin could not confirm your subscription right now. Try again in a moment."
            } finally { if (fromBuy) busy = false }
        }
    }

    /** Play's own page for this subscription (Manage Subscription). */
    fun manageUrl(packageName: String) = "https://play.google.com/store/account/subscriptions?sku=$PRODUCT&package=$packageName"

    fun clear() { plans = emptyList(); loaded = false; busy = false; notice = null; details = null; checkedThisRun = false }
}
