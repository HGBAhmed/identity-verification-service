package sn.sensoft.identity.service;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.multipart.CompletedFileUpload;
import jakarta.inject.Singleton;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Singleton
public class PdfConversionService {

    private static final Logger log = LoggerFactory.getLogger(PdfConversionService.class);

    private final String tempPath;
    private final float defaultDpi;
    private final String outputFormat;

    public PdfConversionService(@Value("${app.file-storage.temp-path}") String tempPath,
                                @Value("${app.pdf-conversion.dpi:300}") float defaultDpi,
                                @Value("${app.pdf-conversion.output-format:jpg}") String outputFormat) {
        this.tempPath = tempPath;
        this.defaultDpi = defaultDpi;
        this.outputFormat = outputFormat.toLowerCase();

        log.info("PdfConversionService initialisé - DPI par défaut: {}, Format: {}, Temp: {}", defaultDpi, outputFormat, tempPath);
        createTempDirectory();
    }

    /**
     * Convertit un PDF en image (première page) avec DPI adaptatif
     */
    public PdfConversionResult convertPdfToImage(CompletedFileUpload pdfFile) throws IOException {
        log.debug("Début conversion PDF vers image - Fichier: {}, Taille: {} bytes",
                pdfFile.getFilename(), pdfFile.getSize());

        if (pdfFile.getSize() == 0) {
            throw new IOException("Fichier PDF vide");
        }

        // Choisir le DPI optimal basé sur la taille du fichier
        float adaptiveDpi = chooseDpiForFile(pdfFile);
        log.info("DPI adaptatif choisi: {} pour fichier {} ({} bytes)",
                adaptiveDpi, pdfFile.getFilename(), pdfFile.getSize());

        try (PDDocument document = Loader.loadPDF(pdfFile.getBytes())) {

            int pageCount = document.getNumberOfPages();
            log.debug("Document PDF chargé - Pages: {}", pageCount);

            if (pageCount == 0) {
                throw new IOException("Document PDF sans pages");
            }

            // Convertir la première page avec le DPI adaptatif
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(0, adaptiveDpi); // Page 0 = première page

            log.debug("Page convertie - Dimensions: {}x{}, DPI: {}",
                    image.getWidth(), image.getHeight(), adaptiveDpi);

            // Validation de l'image convertie
            validateConvertedImage(image);

            // Sauvegarder en fichier temporaire
            String tempFilePath = createTempImageFile(image, pdfFile.getFilename());

            // Créer un nouveau CompletedFileUpload simulé
            ConvertedImageFile convertedFile = new ConvertedImageFile(tempFilePath, outputFormat);

            log.info("Conversion PDF réussie - Fichier: {} → {}, Dimensions: {}x{}, DPI: {}",
                    pdfFile.getFilename(), tempFilePath, image.getWidth(), image.getHeight(), adaptiveDpi);

            return PdfConversionResult.success(
                    convertedFile,
                    tempFilePath,
                    image.getWidth(),
                    image.getHeight(),
                    pageCount,
                    adaptiveDpi
            );

        } catch (IOException e) {
            log.error("Erreur conversion PDF: {} - {}", pdfFile.getFilename(), e.getMessage(), e);
            throw new IOException("Erreur conversion PDF: " + e.getMessage(), e);
        }
    }

    /**
     * Choisit le DPI optimal basé sur la taille du fichier PDF
     */
    private float chooseDpiForFile(CompletedFileUpload file) {
        long fileSize = file.getSize();

        // Logique adaptative basée sur la taille
        if (fileSize > 500_000) { // > 500KB = bonne qualité originale
            log.debug("Fichier de bonne qualité détecté ({}KB), utilisation 150 DPI", fileSize / 1024);
            return 150f; // DPI bas pour éviter le sur-échantillonnage
        } else if (fileSize > 100_000) { // 100KB - 500KB = qualité moyenne
            log.debug("Fichier de qualité moyenne détecté ({}KB), utilisation 200 DPI", fileSize / 1024);
            return 200f; // DPI moyen - compromis
        } else { // < 100KB = probablement compressé/mauvaise qualité
            log.debug("Fichier de faible qualité détecté ({}KB), utilisation 300 DPI", fileSize / 1024);
            return 300f; // DPI élevé pour compenser la qualité
        }
    }

    /**
     * Validation de l'image convertie
     */
    private void validateConvertedImage(BufferedImage image) throws IOException {
        if (image == null) {
            throw new IOException("Échec conversion PDF: image null");
        }

        // Vérifier dimensions minimales
        if (image.getWidth() < 200 || image.getHeight() < 200) {
            throw new IOException(String.format(
                    "Image convertie trop petite: %dx%d (minimum 200x200)",
                    image.getWidth(), image.getHeight()
            ));
        }

        // Vérifier ratio d'aspect
        double ratio = (double) image.getWidth() / image.getHeight();
        if (ratio < 0.1 || ratio > 10.0) {
            log.warn("Ratio d'aspect inhabituel après conversion PDF: {:.2f}", ratio);
        }

        log.debug("Image convertie validée - {}x{}, ratio: {:.2f}",
                image.getWidth(), image.getHeight(), ratio);
    }

    /**
     * Crée un fichier temporaire avec l'image convertie
     */
    private String createTempImageFile(BufferedImage image, String originalFilename) throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String baseFilename = originalFilename.replaceAll("\\.[^.]*$", ""); // Supprimer extension
        String tempFilename = String.format("converted_%s_%s_%s.%s",
                timestamp, uniqueId, baseFilename, outputFormat);

        Path tempFilePath = Paths.get(tempPath, tempFilename);
        createTempDirectory();

        // Sauvegarder l'image
        String formatName = outputFormat.equals("jpg") ? "jpeg" : outputFormat;
        boolean written = ImageIO.write(image, formatName, tempFilePath.toFile());

        if (!written) {
            throw new IOException("Impossible d'écrire l'image convertie au format " + outputFormat);
        }

        log.debug("Fichier image temporaire créé: {} (taille: {} bytes)",
                tempFilePath, Files.size(tempFilePath));

        return tempFilePath.toString();
    }

    /**
     * Crée le répertoire temporaire si nécessaire
     */
    private void createTempDirectory() {
        try {
            Path tempDir = Paths.get(tempPath);
            if (!Files.exists(tempDir)) {
                Files.createDirectories(tempDir);
                log.info("Dossier temporaire créé pour conversion PDF: {}", tempPath);
            }
        } catch (IOException e) {
            throw new RuntimeException("Impossible de créer le dossier temporaire: " + tempPath, e);
        }
    }

    /**
     * Nettoyage des fichiers temporaires de conversion
     */
    public void cleanupConvertedFiles() {
        try {
            Path tempDir = Paths.get(tempPath);
            if (Files.exists(tempDir)) {
                Files.list(tempDir)
                        .filter(path -> path.getFileName().toString().startsWith("converted_"))
                        .filter(path -> {
                            try {
                                LocalDateTime fileTime = LocalDateTime.ofInstant(
                                        Files.getLastModifiedTime(path).toInstant(),
                                        java.time.ZoneId.systemDefault()
                                );
                                return fileTime.isBefore(LocalDateTime.now().minusMinutes(60)); // TTL 1 heure
                            } catch (IOException e) {
                                return true; // Supprimer en cas d'erreur
                            }
                        })
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                                log.debug("Fichier converti expiré supprimé: {}", path);
                            } catch (IOException e) {
                                log.error("Erreur suppression fichier converti: {}", path, e);
                            }
                        });
            }
        } catch (IOException e) {
            log.error("Erreur nettoyage fichiers convertis", e);
        }
    }

    /**
     * Supprime un fichier converti spécifique
     */
    public boolean deleteConvertedFile(String filePath) {
        try {
            boolean deleted = Files.deleteIfExists(Paths.get(filePath));
            if (deleted) {
                log.debug("Fichier converti supprimé: {}", filePath);
            }
            return deleted;
        } catch (IOException e) {
            log.error("Erreur suppression fichier converti: {}", filePath, e);
            return false;
        }
    }

    // CLASSES DE RÉSULTAT

    public static class PdfConversionResult {
        private final boolean success;
        private final ConvertedImageFile convertedFile;
        private final String tempFilePath;
        private final int imageWidth;
        private final int imageHeight;
        private final int pdfPageCount;
        private final float usedDpi; // Nouveau champ pour tracer le DPI utilisé
        private final String error;

        private PdfConversionResult(boolean success, ConvertedImageFile convertedFile,
                                    String tempFilePath, int imageWidth, int imageHeight,
                                    int pdfPageCount, float usedDpi, String error) {
            this.success = success;
            this.convertedFile = convertedFile;
            this.tempFilePath = tempFilePath;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.pdfPageCount = pdfPageCount;
            this.usedDpi = usedDpi;
            this.error = error;
        }

        public static PdfConversionResult success(ConvertedImageFile convertedFile, String tempFilePath,
                                                  int imageWidth, int imageHeight, int pdfPageCount, float usedDpi) {
            return new PdfConversionResult(true, convertedFile, tempFilePath,
                    imageWidth, imageHeight, pdfPageCount, usedDpi, null);
        }

        // Méthode de compatibilité
        public static PdfConversionResult success(ConvertedImageFile convertedFile, String tempFilePath,
                                                  int imageWidth, int imageHeight, int pdfPageCount) {
            return success(convertedFile, tempFilePath, imageWidth, imageHeight, pdfPageCount, 0f);
        }

        public static PdfConversionResult error(String error) {
            return new PdfConversionResult(false, null, null, 0, 0, 0, 0f, error);
        }

        // Getters
        public boolean isSuccess() { return success; }
        public ConvertedImageFile getConvertedFile() { return convertedFile; }
        public String getTempFilePath() { return tempFilePath; }
        public int getImageWidth() { return imageWidth; }
        public int getImageHeight() { return imageHeight; }
        public int getPdfPageCount() { return pdfPageCount; }
        public float getUsedDpi() { return usedDpi; } // Nouveau getter
        public String getError() { return error; }
    }

    /**
     * Wrapper pour simuler un CompletedFileUpload à partir d'un fichier converti
     */
    public static class ConvertedImageFile implements CompletedFileUpload {
        private final String filePath;
        private final String filename;
        private final io.micronaut.http.MediaType contentType;
        private final byte[] bytes;

        public ConvertedImageFile(String filePath, String outputFormat) throws IOException {
            this.filePath = filePath;
            this.filename = Paths.get(filePath).getFileName().toString();
            this.contentType = io.micronaut.http.MediaType.of("image/" + (outputFormat.equals("jpg") ? "jpeg" : outputFormat));
            this.bytes = Files.readAllBytes(Paths.get(filePath));
        }

        public String getFilename() {
            return filename;
        }

        public java.util.Optional<io.micronaut.http.MediaType> getContentType() {
            return java.util.Optional.of(contentType);
        }

        public boolean isEmpty() {
            return bytes.length == 0;
        }

        public long getSize() {
            return bytes.length;
        }

        public long getDefinedSize() {
            return bytes.length;
        }

        public byte[] getBytes() throws IOException {
            return bytes;
        }

        public java.io.InputStream getInputStream() throws IOException {
            return new java.io.ByteArrayInputStream(bytes);
        }

        public boolean isComplete() {
            return true;
        }

        public java.nio.ByteBuffer getByteBuffer() throws IOException {
            return java.nio.ByteBuffer.wrap(bytes);
        }

        public String getName() {
            return "convertedFile";
        }

        // Méthodes utilitaires
        public void transferTo(String location) throws IOException {
            Files.write(Paths.get(location), bytes);
        }

        public void transferTo(java.io.File dest) throws IOException {
            Files.write(dest.toPath(), bytes);
        }

        public void transferTo(java.nio.file.Path dest) throws IOException {
            Files.write(dest, bytes);
        }

        public void discard() {
            // Cleanup par le service
        }
    }
}