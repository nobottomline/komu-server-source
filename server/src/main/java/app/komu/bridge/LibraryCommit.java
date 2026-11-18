/*
 * Komu library commit ABI.
 */
package app.komu.bridge;

/**
 * Raw JNI surface implemented by the iOS host (Komu's JVMLauncher.c).
 *
 * Komu runs a launch that may migrate on a copy of the library and makes the
 * copy the library only after the server reported that its migrations are
 * durable ({@link #libraryPrepared()}). The host then flips its pointer and
 * reports back; until {@link #libraryCommitted()} is true the server must not
 * start work that changes data shared by every library (downloads, automatic
 * backups) or that the user would expect to persist.
 */
public final class LibraryCommit {
    static {
        System.loadLibrary("komubridge");
    }

    private LibraryCommit() {
    }

    public static native int abiVersion();

    /** Migrations ran and the database was checkpointed and synced. */
    public static native void libraryPrepared();

    /** Whether the host made this launch's library the committed one. */
    public static native boolean libraryCommitted();
}
