@file:Suppress("DEPRECATION")

package mozilla.lockbox.presenter

import android.app.KeyguardManager
import android.content.Context
import android.hardware.fingerprint.FingerprintManager
import androidx.test.core.app.ApplicationProvider
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import mozilla.lockbox.action.DataStoreAction
import mozilla.lockbox.action.FingerprintAuthAction
import mozilla.lockbox.action.RouteAction
import mozilla.lockbox.action.UnlockingAction
import mozilla.lockbox.flux.Dispatcher
import mozilla.lockbox.store.FingerprintStore
import mozilla.lockbox.store.LockedStore
import mozilla.lockbox.store.SettingStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.spy
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(packageName = "mozilla.lockbox")
class LockedPresenterTest {

    open class FakeView : LockedView {
        val unlockConfirmedStub = PublishSubject.create<Boolean>()
        override val unlockConfirmed: Observable<Boolean> get() = unlockConfirmedStub
        override val unlockButtonTaps = PublishSubject.create<Unit>()
    }

    open class FakeFingerprintStore : FingerprintStore() {
        private val context: Context = ApplicationProvider.getApplicationContext()

        override var fingerprintManager: FingerprintManager? =
            context.getSystemService(Context.FINGERPRINT_SERVICE) as? FingerprintManager

        override var keyguardManager: KeyguardManager =
            spy(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
    }

    class FakeLockedStore : LockedStore() {
        val onAuth = PublishSubject.create<FingerprintAuthAction>()
        override val onAuthentication: Observable<FingerprintAuthAction>
            get() = onAuth

        override val canLaunchAuthenticationOnForeground: Observable<Boolean> = PublishSubject.create()
    }

    class FakeSettingStore : SettingStore() {
        val unlock = PublishSubject.create<Boolean>()
        override var unlockWithFingerprint: Observable<Boolean> = unlock
    }

    val view: FakeView = spy(FakeView())
    private val fingerprintStore: FakeFingerprintStore = spy(FakeFingerprintStore())
    private val lockedStore = FakeLockedStore()
    private val settingStore = FakeSettingStore()
    private val dispatcher = Dispatcher()
    private lateinit var context: Context
    val subject = LockedPresenter(
        view,
        dispatcher,
        fingerprintStore,
        lockedStore,
        settingStore
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        subject.onViewReady()
    }

    @Test
    fun `unlock button tap shows fingerprint dialog`() {
        `when`(fingerprintStore.isFingerprintAuthAvailable).thenReturn(true)

        val dispatchIterator = dispatcher.register.blockingIterable().iterator()
        view.unlockButtonTaps.onNext(Unit)
        val unlockingAction = dispatchIterator.next() as UnlockingAction
        assertTrue(unlockingAction.currently)
        settingStore.unlock.onNext(true)
        val routeAction = dispatchIterator.next() as RouteAction.DialogFragment
        assertTrue(routeAction is RouteAction.DialogFragment.FingerprintDialog)
    }

    @Test
    fun `unlock button tap fallback if no fingerprint`() {
        `when`(fingerprintStore.isFingerprintAuthAvailable).thenReturn(false)
        `when`(fingerprintStore.isKeyguardDeviceSecure).thenReturn(true)

        val dispatchIterator = dispatcher.register.blockingIterable().iterator()
        view.unlockButtonTaps.onNext(Unit)
        settingStore.unlock.onNext(false)
        assertTrue(dispatchIterator.next() is UnlockingAction)
        assertEquals(RouteAction.UnlockFallbackDialog, dispatchIterator.next())
    }

    @Test
    fun `unlock button tap fallback on fingerprint error`() {
        `when`(fingerprintStore.isFingerprintAuthAvailable).thenReturn(true)
        `when`(fingerprintStore.isKeyguardDeviceSecure).thenReturn(true)

        val dispatchIterator = dispatcher.register.blockingIterable().iterator()
        lockedStore.onAuth.onNext(FingerprintAuthAction.OnError)
        assertEquals(RouteAction.UnlockFallbackDialog, dispatchIterator.next())
    }

    @Test
    fun `onviewready when can launch authentication shows fingerprint dialog`() {
        `when`(fingerprintStore.isFingerprintAuthAvailable).thenReturn(true)

        val dispatchIterator = dispatcher.register.blockingIterable().iterator()
        (lockedStore.canLaunchAuthenticationOnForeground as Subject).onNext(true)
        settingStore.unlock.onNext(true)
        val unlockingAction = dispatchIterator.next() as UnlockingAction
        assertTrue(unlockingAction.currently)
        val routeAction = dispatchIterator.next() as RouteAction.DialogFragment
        assertTrue(routeAction is RouteAction.DialogFragment.FingerprintDialog)
    }

    @Test
    fun `onviewready when can launch authentication if no fingerprint`() {
        `when`(fingerprintStore.isFingerprintAuthAvailable).thenReturn(false)
        `when`(fingerprintStore.isKeyguardDeviceSecure).thenReturn(true)

        val dispatchIterator = dispatcher.register.blockingIterable().iterator()
        (lockedStore.canLaunchAuthenticationOnForeground as Subject).onNext(true)

        settingStore.unlock.onNext(false)
        val unlockingAction = dispatchIterator.next() as UnlockingAction
        assertTrue(unlockingAction.currently)
        assertEquals(RouteAction.UnlockFallbackDialog, dispatchIterator.next())
    }

    @Test
    fun `foreground action fallback on fingerprint error`() {
        `when`(fingerprintStore.isFingerprintAuthAvailable).thenReturn(true)
        `when`(fingerprintStore.isKeyguardDeviceSecure).thenReturn(true)

        val dispatchIterator = dispatcher.register.blockingIterable().iterator()
        lockedStore.onAuth.onNext(FingerprintAuthAction.OnError)
        assertEquals(RouteAction.UnlockFallbackDialog, dispatchIterator.next())
    }

    @Test
    fun `handle success authentication callback`() {
        val dispatchIterator = dispatcher.register.blockingIterable().iterator()
        lockedStore.onAuth.onNext(FingerprintAuthAction.OnSuccess)

        assertEquals(DataStoreAction.Unlock, dispatchIterator.next())
        val unlockingAction = dispatchIterator.next() as UnlockingAction
        assertFalse(unlockingAction.currently)
    }

    @Test
    fun `handle error authentication callback when the device has no other security`() {
        `when`(fingerprintStore.isKeyguardDeviceSecure).thenReturn(false)
        val dispatchIterator = dispatcher.register.blockingIterable().iterator()
        lockedStore.onAuth.onNext(FingerprintAuthAction.OnError)

        assertEquals(DataStoreAction.Unlock, dispatchIterator.next())
        val unlockingAction = dispatchIterator.next() as UnlockingAction
        assertFalse(unlockingAction.currently)
    }

    @Test
    fun `handle unlock confirmed true`() {
        val dispatchIterator = dispatcher.register.blockingIterable().iterator()
        view.unlockConfirmedStub.onNext(true)

        assertEquals(DataStoreAction.Unlock, dispatchIterator.next())
        val unlockingAction = dispatchIterator.next() as UnlockingAction
        assertFalse(unlockingAction.currently)
    }
}
