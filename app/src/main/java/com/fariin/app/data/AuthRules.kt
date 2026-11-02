package com.fariin.app.data

import java.text.Normalizer
import kotlin.random.Random

/**
 * The pure rules behind the sign-up and sign-in pages, ported from the iOS sources so both apps
 * accept and refuse the same things:
 *  - PasswordRules / PasswordChecklist (Kulan-iOS Views/PasswordView.swift)
 *  - the email box, its domain chips and its check (Views/AuthFlowViews.swift EmailAuthView)
 *  - the 6-digit code boxes (Views/LoginCodeView.swift CodeBoxes)
 *  - the username ideas and the handle check words (Views/RootView.swift OnboardingView)
 *  - the doors and their Firebase provider ids (Services/AuthService.swift SignInMethod)
 *  - the saved-account list (Services/LastAccount.swift)
 * No Android or Firebase types here, so all of it runs in plain unit tests.
 */

/** PasswordRules: 8+ characters, a letter, a number, not guessable, and the confirm box matches. */
data class PasswordRules(val password: String, val confirm: String) {
    val longEnough: Boolean get() = password.length >= 8
    val hasLetter: Boolean get() = password.any { it.isLetter() }
    val hasNumber: Boolean get() = password.any { it.isDigit() }
    val matches: Boolean get() = confirm.isNotEmpty() && confirm == password
    val allMet: Boolean get() = longEnough && hasLetter && hasNumber && !guessable && matches

    /** Common words, keyboard runs, too few different characters, or mostly 1-2 / b-c steps. */
    val guessable: Boolean get() {
        val p = password.lowercase()
        if (p.isEmpty()) return false
        if (p.toSet().size <= 3) return true
        val core = p.trim { !it.isLetter() }
        if (core in COMMON || p in COMMON) return true
        if (KEYBOARD_RUNS.any { p.contains(it) } && core.length <= 8) return true
        val chars = p.codePoints().toArray()
        if (chars.size <= 1) return false
        var predictable = 0
        for (i in 1 until chars.size) {
            val a = chars[i - 1]; val b = chars[i]
            val sameKind = (Character.isDigit(a) && Character.isDigit(b)) || (Character.isLetter(a) && Character.isLetter(b))
            if (sameKind && kotlin.math.abs(b - a) <= 1) predictable++
        }
        return predictable.toDouble() >= (chars.size - 1) * 0.6
    }

    /** 0 too short, 1 weak, 2 fair, 3 good, 4 strong (the four bars). */
    val level: Int get() {
        if (!longEnough) return 0
        if (!hasLetter || !hasNumber || guessable) return 1
        if (password.length < 12) return 2
        val mixed = password.any { it.isLowerCase() } && password.any { it.isUpperCase() }
        val symbol = password.any { !it.isLetterOrDigit() }
        return if (mixed || symbol) 4 else 3
    }

    /** The word at the right of the checklist, empty while nothing is typed. */
    val word: String get() = if (password.isEmpty()) "" else WORDS[level]

    /** The one hint at the left: what to do next, or the match result. */
    data class Hint(val text: String, val ok: Boolean, val bad: Boolean)

    val hint: Hint? get() {
        if (password.isEmpty()) return null
        if (!longEnough) {
            val n = 8 - password.length
            return Hint("$n more ${if (n == 1) "character" else "characters"}", ok = false, bad = false)
        }
        if (!hasNumber) return Hint("Add a number", false, false)
        if (!hasLetter) return Hint("Add a letter", false, false)
        if (guessable) return Hint("Too easy to guess", false, true)
        if (confirm.isEmpty()) return null
        return if (matches) Hint("Passwords match", true, false) else Hint("Passwords don't match", false, true)
    }

    companion object {
        val WORDS = listOf("Too short", "Weak", "Fair", "Good", "Strong")
        /** The server's completeSignup check (functions-loginalert), the floor under the checklist. */
        fun serverAccepts(p: String): Boolean = p.length >= 8 && p.any { it in 'A'..'Z' || it in 'a'..'z' } && p.any { it in '0'..'9' }
        private val COMMON = setOf(
            "password", "passw", "passwd", "passwor", "qwerty", "qwertyuiop", "asdfgh", "asdfghjkl", "letmein",
            "welcome", "admin", "iloveyou", "monkey", "dragon", "football", "baseball", "soccer", "sunshine",
            "princess", "master", "shadow", "superman", "batman", "trustno", "hello", "freedom", "whatever",
            "secret", "login", "abc", "abcd", "fariin", "somalia", "somali", "allah", "bismillah", "love",
        )
        private val KEYBOARD_RUNS = listOf("qwert", "asdfg", "zxcvb", "qazwsx", "1qaz", "zaq1")
    }
}

/** The email box on the sign-up and log-in pages. */
object EmailRules {
    val DOMAINS = listOf("gmail.com", "yahoo.com", "hotmail.com", "icloud.com", "outlook.com")

    /** iOS emailLooksValid: something before an @, and a dotted domain after it with no second @. */
    fun looksValid(email: String): Boolean {
        val t = email.trim()
        val at = t.indexOf('@')
        if (at <= 0) return false
        val domain = t.substring(at + 1)
        return domain.contains('.') && !domain.endsWith('.') && !domain.contains('@')
    }

    /** The domain chips over the keyboard, only while the box has focus and a name is typed. */
    fun domainChips(email: String, focused: Boolean): List<String> {
        if (!focused) return emptyList()
        val t = email.trim()
        val at = t.indexOf('@')
        val name = if (at < 0) t else t.substring(0, at)
        if (name.isEmpty()) return emptyList()
        val typed = if (at < 0) "" else t.substring(at + 1).lowercase()
        return DOMAINS.filter { it.startsWith(typed) && it != typed }
    }

    fun applyDomain(email: String, domain: String): String {
        val t = email.trim()
        val at = t.indexOf('@')
        return (if (at < 0) t else t.substring(0, at)) + "@" + domain
    }

    /** Apple's "Hide My Email" relay: a real inbox, but nobody can type it from memory. */
    fun isRelayAddress(a: String): Boolean = a.lowercase().endsWith("@privaterelay.appleid.com")
}

/** CodeBoxes: digits only, at most six; the sixth digit sends it by itself. */
object CodeEntry {
    const val LENGTH = 6
    fun clean(input: String, length: Int = LENGTH): String = input.filter { it in '0'..'9' }.take(length)
    fun isComplete(code: String, length: Int = LENGTH): Boolean = code.length == length && code.all { it in '0'..'9' }
    /** The box with the caret: the next empty one, none when full. */
    fun caretIndex(code: String, length: Int = LENGTH): Int? = if (code.length >= length) null else code.length
    /** "Send another code in 12s" / "Send another code". */
    fun resendLabel(secondsLeft: Int): String = if (secondsLeft > 0) "Send another code in ${secondsLeft}s" else "Send another code"
    const val RESEND_SECONDS = 30
}

/** LoginCodeView.Purpose: the four reasons the code page opens, with their iOS words. */
enum class CodePurpose(val title: String, val blurb: String, val action: String) {
    /** "Forgot password?": a code that logs you in, then an optional new password. */
    Forgot("Enter your code", "Type the email on your account. We will send a code that logs you in, no password needed.", "Log in"),
    SignUp("Confirm your email", "One code and your account is ready.", "Confirm"),
    /** An email account that never proved its address, once, right after the password. */
    Unproven("Confirm your email", "This is a one-time check. It will not be asked again.", "Confirm"),
    /** Sign-up: the code comes before the password, and gives a ticket. */
    NewAccount("Check your email", "Type the code we send you, then choose a password.", "Continue");

    fun codeBlurb(email: String) = "Enter the 6-digit code we sent to ${email.trim()}. It expires in 10 minutes."

    companion object {
        const val EMAIL_STEP_TITLE = "Get a login code"
        const val NEW_PASSWORD_TITLE = "Create a new password"
        const val NEW_PASSWORD_BLURB = "You will use it to log in next time. Or skip this and keep logging in with a code."
    }
}

/** The username box on "Create your profile": its state and the line under it. */
enum class HandleState { Empty, Invalid, Checking, Free, Taken, Unknown }

object HandleRules {
    /** The line under the username box (iOS OnboardingView). */
    fun note(state: HandleState, handle: String, hasIdeas: Boolean): String {
        val h = Usernames.sanitize(handle)
        return when (state) {
            HandleState.Taken -> if (hasIdeas) "@$h is taken. These are free:" else "@$h is taken. Try adding a number."
            HandleState.Free -> "@$h is available."
            HandleState.Checking -> "Checking…"
            HandleState.Unknown -> "Couldn't check @$h. Check your connection."
            HandleState.Empty, HandleState.Invalid -> "Letters, numbers and _ only, ${Usernames.MIN}–${Usernames.MAX} characters."
        }
    }

    /** The state before any server is asked: empty, a bad shape, or worth checking. */
    fun localState(handle: String): HandleState {
        val h = Usernames.sanitize(handle)
        return when {
            h.isEmpty() -> HandleState.Empty
            !Usernames.isValid(h) -> HandleState.Invalid
            else -> HandleState.Checking
        }
    }

    /** checkUsername's answer: free, or taken (no reason or "taken"), else a bad shape. */
    fun fromServer(available: Boolean, reason: String?): HandleState =
        if (available) HandleState.Free else if (reason == null || reason == "taken") HandleState.Taken else HandleState.Invalid

    /** Continue needs a name and a free (or uncheckable: the server decides) username. */
    fun canContinue(name: String, state: HandleState, saving: Boolean): Boolean =
        name.isNotBlank() && (state == HandleState.Free || state == HandleState.Unknown) && !saving

    /**
     * OnboardingView.handleIdeas: usernames made from a typed name, in the iOS order (joined,
     * under_scored, then with 2 or 3 random digits), each padded to 5 and a valid shape.
     */
    fun ideas(raw: String, rnd: Random = Random.Default): List<String> {
        val folded = Normalizer.normalize(raw, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
        val words = folded.split(Regex("[^\\p{L}\\p{N}]+"))
            .map { w -> w.filter { it in 'a'..'z' || it in '0'..'9' } }
            .filter { it.isNotEmpty() }
        if (words.isEmpty() || words.joinToString("").none { it.isLetter() }) return emptyList()
        val joined = words.joinToString("")
        val under = words.joinToString("_")
        fun pad(s: String): String { var t = s; while (t.length < Usernames.MIN) t += rnd.nextInt(0, 10).toString(); return t }
        val cands = listOf(
            joined, under,
            joined + rnd.nextInt(10, 100), under + rnd.nextInt(10, 100),
            joined + rnd.nextInt(100, 1000), joined + "_" + rnd.nextInt(10, 100),
        ).map { pad(it.take(Usernames.MAX)) }
        val seen = HashSet<String>()
        return cands.filter { Usernames.isValid(it) && Usernames.shapeProblem(it) == null && seen.add(it) }
    }

    /** The save checks before the server is asked (iOS OnboardingView.save). Null when fine. */
    fun saveProblem(name: String, handle: String, currentHandle: String): String? {
        if (name.isBlank()) return "Enter your name"
        val h = Usernames.sanitize(handle)
        val unchanged = h.isNotEmpty() && h.equals(currentHandle, ignoreCase = true)
        if (!unchanged && !Usernames.isValid(h)) return "Username: letters, numbers and _ only, ${Usernames.MIN}–${Usernames.MAX} characters"
        return null
    }
}

/** AuthService.SignInMethod: the three doors and the Firebase provider id behind each. */
enum class SignInMethod(val raw: String, val providerId: String) {
    Apple("apple", "apple.com"), Google("google", "google.com"), Email("email", "password");

    companion object {
        fun of(raw: String?): SignInMethod? = entries.firstOrNull { it.raw == raw }
        fun forProvider(providerId: String?): SignInMethod? = entries.firstOrNull { it.providerId == providerId }

        /** LastAccount.doorOf: this phone's last door when the account has it, else email, Google, Apple. */
        fun doorOf(providers: Set<String>, last: SignInMethod?): SignInMethod? {
            if (last != null && last.providerId in providers) return last
            return listOf(Email, Google, Apple).firstOrNull { it.providerId in providers }
        }
    }
}

/** LastAccount.Info: one saved account card. Nothing in it signs anybody in. */
data class SavedAccount(
    val uid: String,
    val name: String,
    val handle: String,
    val method: String,
    val email: String? = null,
    val verified: Boolean? = null,
    val gold: Boolean? = null,
) {
    /** The card's first line: the name, or @handle when there is none. */
    val title: String get() = if (name.isEmpty()) "@$handle" else name

    companion object {
        const val LIMIT = 5

        /** LastAccount.remember: this account to the front, refreshed, at most five; returns the dropped ones too. */
        fun upsert(list: List<SavedAccount>, info: SavedAccount, limit: Int = LIMIT): Pair<List<SavedAccount>, List<SavedAccount>> {
            if (list.firstOrNull() == info) return list to emptyList()
            val next = listOf(info) + list.filter { it.uid != info.uid }
            return next.take(limit) to next.drop(limit)
        }

        /** The second line: "Last used" on the newest card when there is more than one, else @handle. */
        fun showsLastUsed(list: List<SavedAccount>, a: SavedAccount): Boolean = list.size > 1 && list.firstOrNull()?.uid == a.uid
    }
}

/** AuthFlowError: the flow's own refusals, with the iOS sentences. */
class AuthFlowError(val kind: Kind) : Exception(kind.message) {
    enum class Kind(val message: String) {
        AppleFailed("Apple sign-in didn't complete. Please try again."),
        GoogleFailed("Google sign-in didn't complete. Please try again."),
        EmailTaken("This email already has an account. Go back and choose Log In, then use the way you signed up or log in with a code."),
        NotSignedIn("You're not signed in."),
        NoAccount("You haven't signed up with this account before. Go back and choose Sign Up to continue."),
        SignupExpired("Your code has expired. Press Continue for a new one."),
        PasskeyFailed("That passkey was not recognised."),
    }
}

/** AuthService.plainMessage and the door page's own words, keyed by Firebase's Android error codes. */
object AuthMessages {
    const val OFFLINE = "No internet connection. Check your connection and try again."
    const val OFFLINE_SHORT = "No internet connection. Try again."
    const val SOMETHING_WRONG = "Something went wrong. Try again."

    /** iOS plainMessage, by FirebaseAuthException.errorCode (the iOS numeric codes in the comments). */
    fun forAuthCode(code: String?, credentialHint: String = "Wrong email or password."): String? = when (code) {
        "ERROR_INVALID_EMAIL" -> "That doesn't look like an email address."                    // 17008
        "ERROR_WEAK_PASSWORD" -> "Password must be at least 6 characters."                      // 17026
        "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL" -> credentialHint                    // 17009, 17004
        "ERROR_USER_NOT_FOUND" -> "No account with that email. Create one instead."              // 17011
        "ERROR_EMAIL_ALREADY_IN_USE" -> "This email already has an account. Go back and choose Log In, then use the way you signed up or log in with a code."
        "ERROR_USER_DISABLED" -> "This account is suspended and can't sign in right now. If you think this is a mistake, contact us at fariin.com/help."
        "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Wait a moment and try again."            // 17010
        "ERROR_REQUIRES_RECENT_LOGIN" -> "Please sign in again before doing this."                // 17014
        "ERROR_USER_TOKEN_EXPIRED" -> "Your session expired. Sign in again."                     // 17021
        "ERROR_USER_MISMATCH" -> "That's a different account. Choose the account you use for Fariin."
        "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" -> "This email already has an account that signs in a different way. Go back and use the button you signed up with, or log in with a code."
        else -> null
    }

    /** The doors page (Apple, Google, a saved card): only three sentences there. */
    fun forDoorCode(code: String?): String = when (code) {
        "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" -> "This email already has an account, made a different way. Use the button you signed up with, or log in with a code."
        else -> "Couldn't sign in. Please try again."
    }

    /** LoginCodeView.plain: the server's own sentence unless it is the generic "internal". */
    fun forServer(message: String?): String =
        if (!message.isNullOrBlank() && !message.lowercase().contains("internal")) message else SOMETHING_WRONG
}
