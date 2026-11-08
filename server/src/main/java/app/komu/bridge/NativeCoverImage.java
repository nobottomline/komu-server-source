/*
 * Komu native cover-image inspection ABI.
 */
package app.komu.bridge;

/**
 * Raw JNI surface implemented by the iOS host with ImageIO. The embedded
 * OpenJ9 runtime has no AWT or javax.imageio natives, so decoding an image to
 * compare a thumbnail with its candidate original is delegated to the host.
 */
public final class NativeCoverImage {
    static {
        System.loadLibrary("komubridge");
    }

    private NativeCoverImage() {
    }

    public static native int abiVersion();

    /**
     * Decodes {@code image} and returns its oriented pixel width and height
     * (two big-endian int32) followed by a {@code gridSize} x {@code gridSize}
     * 8-bit luma grid of the whole picture, or {@code null} when the bytes are
     * not a decodable image or have more than {@code maximumPixels} pixels.
     */
    public static native byte[] lumaGrid(byte[] image, int gridSize, long maximumPixels);
}
