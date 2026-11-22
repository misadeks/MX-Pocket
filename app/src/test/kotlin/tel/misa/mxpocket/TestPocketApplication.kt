package tel.misa.mxpocket

/** Robolectric doesn't install manifest providers on process start as Android does. */
class TestPocketApplication : PocketApplication() {
    override fun onCreate() {
        super.onCreate()
        checkNotNull(contentResolver.acquireContentProviderClient("$packageName.resources.AndroidContextProvider")).close()
    }
}
