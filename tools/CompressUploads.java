import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

public class CompressUploads {
    private static final Set<String> PASSTHROUGH_EXTENSIONS = Set.of(".gif", ".svg", ".webp");
    private static final int MAX_IMAGE_DIMENSION = 1600;
    private static final float JPEG_QUALITY = 0.80f;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: java CompressUploads.java <uploads-dir>");
            System.exit(1);
        }

        Path uploadDir = Path.of(args[0]);
        if (!Files.isDirectory(uploadDir)) {
            System.err.println("Not a directory: " + uploadDir);
            System.exit(1);
        }

        long totalBefore = 0L;
        long totalAfter = 0L;
        int changed = 0;
        int skipped = 0;

        try (Stream<Path> stream = Files.list(uploadDir)) {
            for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                long before = Files.size(file);
                totalBefore += before;

                Result result = compressFile(file);
                totalAfter += result.afterSize();

                if (result.changed()) {
                    changed++;
                    System.out.printf(
                            Locale.ROOT,
                            "CHANGED %s %d -> %d (%.1f%%)%n",
                            file.getFileName(),
                            before,
                            result.afterSize(),
                            before == 0 ? 0.0 : (100.0 * (before - result.afterSize()) / before)
                    );
                } else {
                    skipped++;
                    System.out.printf(
                            Locale.ROOT,
                            "SKIPPED %s %d (%s)%n",
                            file.getFileName(),
                            before,
                            result.reason()
                    );
                }
            }
        }

        System.out.printf(
                Locale.ROOT,
                "SUMMARY changed=%d skipped=%d total_before=%d total_after=%d saved=%d (%.1f%%)%n",
                changed,
                skipped,
                totalBefore,
                totalAfter,
                Math.max(0L, totalBefore - totalAfter),
                totalBefore == 0 ? 0.0 : (100.0 * (totalBefore - totalAfter) / totalBefore)
        );
    }

    private static Result compressFile(Path file) throws IOException {
        String extension = extensionOf(file.getFileName().toString());
        if (PASSTHROUGH_EXTENSIONS.contains(extension)) {
            return new Result(false, Files.size(file), "passthrough");
        }

        BufferedImage source = ImageIO.read(file.toFile());
        if (source == null) {
            return new Result(false, Files.size(file), "unsupported");
        }

        BufferedImage resized = resizeIfNeeded(source);
        boolean hasAlpha = resized.getColorModel().hasAlpha();
        String formatName = hasAlpha && ".png".equals(extension) ? "png" : "jpeg";
        float quality = "png".equals(formatName) ? 1.0f : JPEG_QUALITY;

        byte[] optimized = writeImage(resized, formatName, quality);
        long beforeSize = Files.size(file);
        if (optimized.length >= beforeSize) {
            return new Result(false, beforeSize, "not_smaller");
        }

        Path tempFile = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tempFile, optimized);
        Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return new Result(true, optimized.length, "optimized");
    }

    private static BufferedImage resizeIfNeeded(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longestSide = Math.max(width, height);

        if (longestSide <= MAX_IMAGE_DIMENSION) {
            return source;
        }

        double scale = (double) MAX_IMAGE_DIMENSION / longestSide;
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        boolean hasAlpha = source.getColorModel().hasAlpha();

        BufferedImage resized = new BufferedImage(
                targetWidth,
                targetHeight,
                hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB
        );

        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (!hasAlpha) {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, targetWidth, targetHeight);
            }
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return resized;
    }

    private static byte[] writeImage(BufferedImage image, String formatName, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(formatName);
        if (!writers.hasNext()) {
            throw new IOException("No writer for " + formatName);
        }

        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                String[] compressionTypes = param.getCompressionTypes();
                if (compressionTypes != null && compressionTypes.length > 0) {
                    param.setCompressionType(compressionTypes[0]);
                }
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), param);
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        String ext = fileName.substring(dot).toLowerCase(Locale.ROOT);
        if (".jpeg".equals(ext)) {
            return ".jpg";
        }
        return ext;
    }

    private record Result(boolean changed, long afterSize, String reason) {
    }
}
