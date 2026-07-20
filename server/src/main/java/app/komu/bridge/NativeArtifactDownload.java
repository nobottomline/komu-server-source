/*
 * Komu native download-to-file ABI for executable extension artifacts.
 */
package app.komu.bridge;

/**
 * Raw JNI surface. The native side returns only a small UTF-8 JSON envelope;
 * artifact bytes remain in an app-owned temporary file.
 */
public final class NativeArtifactDownload {
    static {
        System.loadLibrary("komubridge");
    }

    private NativeArtifactDownload() {
    }

    public static native int abiVersion();

    /** Returns only whether native and JVM staging roots resolve to one directory identity. */
    public static native boolean temporaryRootMatches(byte[] expectedPathUtf8);

    public static native boolean prepare(byte[] operationIdUtf8);

    public static native byte[] downloadToTemporaryFile(
            byte[] operationIdUtf8,
            byte[] urlUtf8,
            long maximumBytes
    );

    public static native void cancel(byte[] operationIdUtf8);
}
