package com.modtranslator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.UIManager;

public class ModLangTranslatorApp {

    private static final Pattern COLOR_CODE_PATTERN = Pattern.compile("§.");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("%(?:\\d+\\$)?[sdif]");
    private static final Pattern BRACE_PATTERN = Pattern.compile("\\{[^{}]+}");
    private static final Pattern PAREN_PATTERN = Pattern.compile("\\([^()]*\\)");
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("\\b[A-Za-z0-9]+(?:[._][A-Za-z0-9]+)+\\b");
    private static final String[] LANG_CODES = {
            "en_us", "es_es", "es_mx", "pt_br", "fr_fr", "de_de", "it_it", "ru_ru", "ja_jp", "ko_kr", "zh_cn"
    };

    private static final String GOOGLE_ENDPOINT = "https://translation.googleapis.com/language/translate/v2";
    private static final String GOOGLE_V3BETA1_ENDPOINT = "https://translate.googleapis.com/v3beta1/{parent=projects/*/locations/*}:translateText";
    private static final String MY_MEMORY_ENDPOINT = "https://api.mymemory.translated.net/get";
    private static final String DEFAULT_ENDPOINTS =
            "https://libretranslate.de/translate\n" +
            "https://translate.terraprint.co/translate\n" +
            "https://libretranslate.com/translate";

    private final JTextField jarPathField = new JTextField();
    private final JTextArea selectedModsArea = new JTextArea(5, 40);
    private final JCheckBox batchCheck = new JCheckBox("Traducción por lote");
    private final JComboBox<String> apiTypeCombo = new JComboBox<>(new String[]{"LibreTranslate", "Google Cloud", "MyMemory (Gratis)"});
    private final JTextArea endpointArea = new JTextArea(DEFAULT_ENDPOINTS, 3, 40);
    private final JTextField apiKeyField = new JTextField();
    private final JComboBox<String> sourceLangCombo = new JComboBox<>(LANG_CODES);
    private final JComboBox<String> targetLangCombo = new JComboBox<>(LANG_CODES);
    private final JTextArea logArea = new JTextArea();
    private final JButton translateButton = new JButton("Translate and Build JAR");
    private final JButton stopButton = new JButton("Stop");
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private volatile SwingWorker<String, String> currentWorker;
    private final List<Path> selectedJarPaths = new ArrayList<>();
    private final Path fileLogPath = Path.of("logs", "mod-lang-translator.log");

    private static final DateTimeFormatter LOG_TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            new ModLangTranslatorApp().show();
        });
    }

    private void show() {
        JFrame frame = new JFrame("Minecraft Mod Lang Translator");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(920, 600));

        JPanel top = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 6, 6, 6);
        c.fill = GridBagConstraints.HORIZONTAL;

        JButton browseButton = new JButton("Browse...");
        browseButton.addActionListener(e -> chooseJar(frame));
        installJarDragAndDrop(jarPathField);

        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        top.add(new JLabel("Mod JAR:"), c);

        c.gridx = 1;
        c.weightx = 1;
        top.add(jarPathField, c);

        c.gridx = 2;
        c.weightx = 0;
        top.add(browseButton, c);

        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0;
        c.anchor = GridBagConstraints.NORTHWEST;
        top.add(new JLabel("Selected Mods:"), c);

        selectedModsArea.setEditable(false);
        selectedModsArea.setLineWrap(true);
        selectedModsArea.setWrapStyleWord(true);
        selectedModsArea.setText("No mods selected yet.");
        JScrollPane selectedModsScroll = new JScrollPane(selectedModsArea);
        c.gridx = 1;
        c.gridwidth = 2;
        c.weightx = 1;
        top.add(selectedModsScroll, c);

        c.gridx = 0;
        c.gridy = 2;
        c.anchor = GridBagConstraints.CENTER;
        c.gridwidth = 1;
        top.add(new JLabel("API Mode:"), c);

        c.gridx = 1;
        c.gridwidth = 2;
        c.weightx = 1;
        top.add(apiTypeCombo, c);

        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 1;
        c.weightx = 0;
        c.anchor = GridBagConstraints.NORTHWEST;
        JLabel endpointLabel = new JLabel("<html>Endpoints<br>(one per line):</html>");
        top.add(endpointLabel, c);

        c.gridx = 1;
        c.gridwidth = 2;
        c.weightx = 1;
        c.anchor = GridBagConstraints.CENTER;
        endpointArea.setFont(endpointArea.getFont().deriveFont(12f));
        endpointArea.setLineWrap(false);
        JScrollPane endpointScroll = new JScrollPane(endpointArea);
        top.add(endpointScroll, c);

        c.gridx = 0;
        c.gridy = 4;
        c.gridwidth = 1;
        c.weightx = 0;
        top.add(new JLabel("API Key:"), c);

        c.gridx = 1;
        c.gridwidth = 2;
        c.weightx = 1;
        top.add(apiKeyField, c);

        // Toggle endpoint area based on API mode
        JLabel apiKeyNote = new JLabel("<html><font color='gray'>(optional for LibreTranslate, required for Google Cloud)</font></html>");
        c.gridx = 1; c.gridy = 5; c.gridwidth = 2;
        top.add(apiKeyNote, c);

        apiTypeCombo.addItemListener(e -> {
            String selected = (String) apiTypeCombo.getSelectedItem();
            boolean isGoogle = "Google Cloud".equals(selected);
            boolean isMyMemory = "MyMemory (Gratis)".equals(selected);
            boolean endpointsVisible = !isGoogle && !isMyMemory;
            endpointArea.setEnabled(endpointsVisible);
            endpointArea.setEditable(endpointsVisible);
            endpointLabel.setEnabled(endpointsVisible);
            endpointScroll.setEnabled(endpointsVisible);
            if (isGoogle) {
                endpointArea.setText(GOOGLE_ENDPOINT);
                apiKeyNote.setText("<html><font color='red'>API Key is required for Google Cloud</font></html>");
            } else if (isMyMemory) {
                endpointArea.setText(MY_MEMORY_ENDPOINT);
                apiKeyNote.setText("<html><font color='gray'>MyMemory: gratis sin key. Pon tu email en API Key para m&aacute;s cuota.</font></html>");
            } else {
                endpointArea.setText(DEFAULT_ENDPOINTS);
                apiKeyNote.setText("<html><font color='gray'>(optional for LibreTranslate, required for Google Cloud)</font></html>");
            }
        });

        c.gridx = 0;
        c.gridy = 6;
        c.gridwidth = 1;
        c.weightx = 0;
        top.add(new JLabel("Source Lang:"), c);

        c.gridx = 1;
        c.gridwidth = 1;
        c.weightx = 1;
        top.add(sourceLangCombo, c);

        c.gridx = 2;
        c.weightx = 1;
        top.add(targetLangCombo, c);

        sourceLangCombo.setSelectedItem("en_us");
        targetLangCombo.setSelectedItem("es_es");

        c.gridx = 0;
        c.gridy = 7;
        c.gridwidth = 1;
        c.weightx = 0;
        top.add(batchCheck, c);

        batchCheck.addActionListener(e -> refreshSelectedModsPreview());

        c.gridx = 1;
        c.gridwidth = 2;
        JPanel actionPanel = new JPanel();
        actionPanel.add(translateButton);
        actionPanel.add(stopButton);
        stopButton.setEnabled(false);
        top.add(actionPanel, c);

        logArea.setEditable(false);
        JScrollPane logScroll = new JScrollPane(logArea);

        frame.add(top, BorderLayout.NORTH);
        frame.add(logScroll, BorderLayout.CENTER);

        translateButton.addActionListener(e -> processJar(frame));
        stopButton.addActionListener(e -> requestStop());

        log("File log path: " + fileLogPath.toAbsolutePath());

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private void installJarDragAndDrop(JTextField targetField) {
        targetField.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }

                try {
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (files.isEmpty()) {
                        return false;
                    }

                    List<Path> droppedJars = new ArrayList<>();
                    for (File file : files) {
                        if (file.getName().toLowerCase().endsWith(".jar")) {
                            droppedJars.add(file.toPath());
                        }
                    }

                    if (droppedJars.isEmpty()) {
                        return false;
                    }

                    if (!batchCheck.isSelected() && droppedJars.size() > 1) {
                        setSelectedJars(List.of(droppedJars.get(0)));
                    } else {
                        setSelectedJars(droppedJars);
                    }
                    return true;
                } catch (Exception ignored) {
                }

                return false;
            }
        });
        targetField.setDragEnabled(true);
    }


    private void chooseJar(JFrame parent) {
        FileDialog dialog = new FileDialog(parent, "Select mod JAR(s)", FileDialog.LOAD);
        dialog.setMultipleMode(batchCheck.isSelected());
        FilenameFilter jarFilter = (dir, name) -> name != null && name.toLowerCase().endsWith(".jar");
        dialog.setFilenameFilter(jarFilter);

        if (!selectedJarPaths.isEmpty()) {
            Path first = selectedJarPaths.get(0);
            Path parentDir = first.getParent();
            if (parentDir != null) {
                dialog.setDirectory(parentDir.toString());
            }
        }

        dialog.setVisible(true);
        File[] files = dialog.getFiles();
        if (files != null && files.length > 0) {
            List<Path> jars = new ArrayList<>();
            for (File file : files) {
                if (file.getName().toLowerCase().endsWith(".jar")) {
                    jars.add(file.toPath());
                }
            }
            if (!jars.isEmpty()) {
                if (!batchCheck.isSelected() && jars.size() > 1) {
                    setSelectedJars(List.of(jars.get(0)));
                } else {
                    setSelectedJars(jars);
                }
            }
        }
    }

    private void setSelectedJars(List<Path> jars) {
        selectedJarPaths.clear();
        selectedJarPaths.addAll(jars);

        if (jars.isEmpty()) {
            jarPathField.setText("");
        } else if (batchCheck.isSelected()) {
            StringBuilder sb = new StringBuilder();
            for (Path jar : jars) {
                sb.append(jar.toAbsolutePath()).append(";");
            }
            jarPathField.setText(sb.toString());
        } else {
            jarPathField.setText(jars.get(0).toAbsolutePath().toString());
        }

        refreshSelectedModsPreview();
    }

    private void processJar(JFrame parent) {
        List<Path> jarsToProcess = collectInputJars();
        if (jarsToProcess.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "Select at least one valid .jar file", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        final String selectedApi = (String) apiTypeCombo.getSelectedItem();
        final boolean useGoogleCloud = "Google Cloud".equals(selectedApi);
        final boolean useMyMemory = "MyMemory (Gratis)".equals(selectedApi);
        final List<String> endpoints;
        if (useGoogleCloud) {
            endpoints = List.of(GOOGLE_ENDPOINT);
            if (apiKeyField.getText().trim().isBlank()) {
                JOptionPane.showMessageDialog(parent, "Google Cloud Translation requires an API key.", "API Key required", JOptionPane.ERROR_MESSAGE);
                return;
            }
        } else if (useMyMemory) {
            endpoints = List.of(MY_MEMORY_ENDPOINT);
        } else {
            endpoints = java.util.Arrays.stream(endpointArea.getText().split("\n"))
                    .map(String::trim).filter(s -> !s.isBlank()).toList();
            if (endpoints.isEmpty()) {
                JOptionPane.showMessageDialog(parent, "Add at least one endpoint", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        final String sourceLangCode = (String) sourceLangCombo.getSelectedItem();
        final String targetLangCode = (String) targetLangCombo.getSelectedItem();
        if (sourceLangCode == null || targetLangCode == null) {
            JOptionPane.showMessageDialog(parent, "Source/target languages are required", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (sourceLangCode.equalsIgnoreCase(targetLangCode)) {
            JOptionPane.showMessageDialog(parent, "Source and target language cannot be the same", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        final List<Path> finalJarsToProcess = List.copyOf(jarsToProcess);
        final int total = finalJarsToProcess.size();

        translateButton.setEnabled(false);
        stopButton.setEnabled(true);
        cancelRequested.set(false);
        logArea.setText("");
        log("File log path: " + fileLogPath.toAbsolutePath());
        log("Batch translation started. Files: " + total);

        SwingWorker<String, String> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() {
                int translated = 0;
                int skippedNoLang = 0;
                int failed = 0;

                for (int i = 0; i < total; i++) {
                    if (cancelRequested.get() || isCancelled()) {
                        return "CANCELLED::Translation cancelled by user. Progress: " + (i) + "/" + total;
                    }

                    Path inputJar = finalJarsToProcess.get(i);
                    Path outputJar = buildOutputJarPath(inputJar, targetLangCode);
                    publish("[" + (i + 1) + "/" + total + "] Starting translation for: " + inputJar.getFileName());

                    Translator translator = new Translator(endpoints, apiKeyField.getText().trim(), selectedApi, this::publish, () -> cancelRequested.get() || isCancelled());
                    try {
                        int fileCount = translateJar(
                                inputJar,
                                outputJar,
                                translator,
                                sourceLangCode,
                                targetLangCode,
                                this::publish,
                                () -> cancelRequested.get() || isCancelled()
                        );
                        translated++;
                        publish("[" + (i + 1) + "/" + total + "] Completed. Generated: " + outputJar.getFileName() + " | translated lang files: " + fileCount);

                        try {
                            Path disabled = inputJar.resolveSibling(inputJar.getFileName() + ".disable");
                            Files.move(inputJar, disabled);
                            publish("[" + (i + 1) + "/" + total + "] Original file renamed to: " + disabled.getFileName());
                        } catch (Exception ex) {
                            publish("[WARN] Could not rename original: " + ex.getMessage());
                        }
                    } catch (NoLangFilesException ex) {
                        skippedNoLang++;
                        publish("[" + (i + 1) + "/" + total + "] NO_LANG::" + ex.getMessage());
                    } catch (ApiBlockedException ex) {
                        publish("BLOCKED::" + ex.getMessage());
                        return "BLOCKED::All endpoints blocked while processing " + inputJar.getFileName();
                    } catch (InterruptedException ex) {
                        if (cancelRequested.get() || isCancelled()) {
                            return "CANCELLED::Translation cancelled by user. Progress: " + (i + 1) + "/" + total;
                        }
                        failed++;
                        publish("[" + (i + 1) + "/" + total + "] Error: " + ex.getMessage());
                    } catch (Exception ex) {
                        failed++;
                        publish("[" + (i + 1) + "/" + total + "] Error: " + ex.getMessage());
                    }
                }

                return "DONE::Batch finished. Total=" + total + ", translated=" + translated + ", no_lang=" + skippedNoLang + ", failed=" + failed;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String chunk : chunks) {
                    log(chunk);
                }
            }

            @Override
            protected void done() {
                translateButton.setEnabled(true);
                stopButton.setEnabled(false);
                currentWorker = null;
                try {
                    String result = get();
                    log(result);
                    if (result.startsWith("CANCELLED::")) {
                        JOptionPane.showMessageDialog(parent, result.substring("CANCELLED::".length()), "Cancelled", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    if (result.startsWith("BLOCKED::")) {
                        JOptionPane.showMessageDialog(parent, result.substring("BLOCKED::".length()), "All endpoints blocked", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    if (result.startsWith("DONE::")) {
                        JOptionPane.showMessageDialog(parent, result.substring("DONE::".length()), "Batch Result", JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (InterruptedException | ExecutionException e) {
                    if (cancelRequested.get()) {
                        log("Translation cancelled by user.");
                    } else {
                        logException("Unexpected UI completion error", e);
                        log("Unexpected error: " + e.getMessage());
                    }
                }
            }
        };

        currentWorker = worker;
        worker.execute();
    }

    private List<Path> collectInputJars() {
        List<Path> jars = new ArrayList<>();
        if (!selectedJarPaths.isEmpty()) {
            jars.addAll(selectedJarPaths);
        } else {
            String jarPaths = jarPathField.getText().trim();
            if (!jarPaths.isBlank()) {
                String[] raw = batchCheck.isSelected() ? jarPaths.split(";") : new String[]{jarPaths};
                for (String part : raw) {
                    String trimmed = part.trim();
                    if (!trimmed.isBlank()) {
                        jars.add(Path.of(trimmed));
                    }
                }
            }
        }

        List<Path> valid = new ArrayList<>();
        for (Path jar : jars) {
            if (jar != null && Files.exists(jar) && jar.toString().toLowerCase().endsWith(".jar")) {
                valid.add(jar);
            }
        }
        return valid;
    }

    private void refreshSelectedModsPreview() {
        List<Path> jars = collectInputJars();
        if (jars.isEmpty()) {
            selectedModsArea.setText("No mods selected yet.");
            return;
        }

        boolean batch = batchCheck.isSelected();
        StringBuilder sb = new StringBuilder();
        sb.append("Selected ").append(jars.size()).append(batch ? " mods (batch)." : " mod.").append("\n");

        int previewLimit = Math.min(jars.size(), 12);
        for (int i = 0; i < previewLimit; i++) {
            sb.append(i + 1).append(") ").append(jars.get(i).getFileName()).append("\n");
        }
        if (jars.size() > previewLimit) {
            sb.append("... and ").append(jars.size() - previewLimit).append(" more files.");
        }
        selectedModsArea.setText(sb.toString());
        selectedModsArea.setCaretPosition(0);
    }

    private void requestStop() {
        cancelRequested.set(true);
        SwingWorker<String, String> worker = currentWorker;
        if (worker != null && !worker.isDone()) {
            log("Cancellation requested... stopping current translation.");
            worker.cancel(true);
        }
        stopButton.setEnabled(false);
    }

    private static Path buildOutputJarPath(Path inputJar, String targetLangCode) {
        String name = inputJar.getFileName().toString();
        String base = name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
        return inputJar.getParent().resolve(base + "_" + targetLangCode + ".jar");
    }

    private static int translateJar(
            Path inputJar,
            Path outputJar,
            Translator translator,
            String sourceLangCode,
            String targetLangCode,
            java.util.function.Consumer<String> logger,
            BooleanSupplier shouldCancel
    ) throws IOException, InterruptedException, NoLangFilesException, ApiBlockedException {

        Map<String, byte[]> translatedLangEntries = new HashMap<>();
        List<String> translatedEntryNames = new ArrayList<>();

        try (ZipFile zipFile = new ZipFile(inputJar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ensureNotCancelled(shouldCancel);
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }

                String entryName = entry.getName();
                if (isSourceLangEntry(entryName, sourceLangCode)) {
                    logger.accept("Found lang file: " + entryName);
                    try (InputStream in = zipFile.getInputStream(entry)) {
                        String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        String translatedJson = translateLangJson(json, translator, sourceLangCode, targetLangCode, logger, entryName, shouldCancel);
                        String targetEntryName = toTargetLangPath(entryName, sourceLangCode, targetLangCode);
                        translatedLangEntries.put(targetEntryName, translatedJson.getBytes(StandardCharsets.UTF_8));
                        translatedEntryNames.add(targetEntryName);
                        logger.accept("Prepared translated file: " + targetEntryName);
                    }
                }
            }

            if (translatedLangEntries.isEmpty()) {
                throw new NoLangFilesException("This mod does not contain lang/" + sourceLangCode + "*.json, so it cannot be translated from that source language.");
            }

            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(outputJar))) {
                entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ensureNotCancelled(shouldCancel);
                    ZipEntry entry = entries.nextElement();
                    String entryName = entry.getName();

                    if (translatedLangEntries.containsKey(entryName)) {
                        continue;
                    }

                    out.putNextEntry(new ZipEntry(entryName));
                    if (!entry.isDirectory()) {
                        try (InputStream in = zipFile.getInputStream(entry)) {
                            copy(in, out);
                        }
                    }
                    out.closeEntry();
                }

                for (Map.Entry<String, byte[]> translated : translatedLangEntries.entrySet()) {
                    ZipEntry newEntry = new ZipEntry(translated.getKey());
                    out.putNextEntry(newEntry);
                    out.write(translated.getValue());
                    out.closeEntry();
                }
            }
        }

        logger.accept("Done writing output JAR: " + outputJar);
        return translatedEntryNames.size();
    }

    private static void ensureNotCancelled(BooleanSupplier shouldCancel) throws InterruptedException {
        if (shouldCancel.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Translation cancelled by user.");
        }
    }

    private static boolean isSourceLangEntry(String entryName, String sourceLangCode) {
        Pattern sourcePattern = Pattern.compile("(^|.*/)lang/" + Pattern.quote(sourceLangCode) + "(?:[_-][A-Za-z0-9-]+)?\\.json$");
        return sourcePattern.matcher(entryName).matches();
    }

    private static String toTargetLangPath(String entryName, String sourceLangCode, String targetLangCode) {
        return entryName.replace(sourceLangCode, targetLangCode);
    }

    private static String translateLangJson(
            String json,
            Translator translator,
            String sourceLangCode,
            String targetLangCode,
            java.util.function.Consumer<String> logger,
            String sourcePath,
            BooleanSupplier shouldCancel
    ) throws InterruptedException, ApiBlockedException {

        Gson gsonPretty = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, ProtectResult> pending = new LinkedHashMap<>();
        Set<String> uniqueBatch = new LinkedHashSet<>();

        int processed = 0;
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            ensureNotCancelled(shouldCancel);
            JsonElement value = e.getValue();
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String original = value.getAsString();
                if (original == null || original.isBlank() || looksLikeNonTranslatable(original)) {
                    processed++;
                    continue;
                }

                ProtectResult protectedText = protectSegments(e.getKey(), original);
                String candidate = protectedText.maskedText;
                if (candidate.trim().isEmpty()) {
                    processed++;
                    continue;
                }

                pending.put(e.getKey(), protectedText);
                uniqueBatch.add(candidate);
                processed++;

                if (processed % 120 == 0) {
                    logger.accept("[" + sourcePath + "] translated strings: " + processed);
                }
            }
        }

        if (!uniqueBatch.isEmpty()) {
            Map<String, String> translatedByCandidate = translator.translateBatch(new ArrayList<>(uniqueBatch), toShortLang(sourceLangCode), toShortLang(targetLangCode));
            for (Map.Entry<String, ProtectResult> pendingEntry : pending.entrySet()) {
                ensureNotCancelled(shouldCancel);
                String key = pendingEntry.getKey();
                ProtectResult protectedText = pendingEntry.getValue();
                String translatedMasked = translatedByCandidate.getOrDefault(protectedText.maskedText, protectedText.maskedText);
                String restored = restoreSegments(translatedMasked, protectedText.tokens);
                root.addProperty(key, normalizeTranslatedValue(key, restored, targetLangCode));
            }
            logger.accept("[" + sourcePath + "] API batch items: " + uniqueBatch.size());
        }

        logger.accept("[" + sourcePath + "] total processed strings: " + processed);
        return gsonPretty.toJson(root);
    }

    private static String toShortLang(String minecraftLangCode) {
        int underscore = minecraftLangCode.indexOf('_');
        if (underscore > 0) {
            return minecraftLangCode.substring(0, underscore);
        }
        return minecraftLangCode;
    }

    private static boolean looksLikeNonTranslatable(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        if (trimmed.startsWith("#") || trimmed.startsWith("@")) {
            return true;
        }

        if (trimmed.matches("^[A-Za-z0-9_.-]+$") && (trimmed.contains(".") || trimmed.contains("_"))) {
            return true;
        }

        return false;
    }

    private static ProtectResult protectSegments(String key, String text) {
        String masked = text;
        List<String> tokens = new ArrayList<>();

        masked = protectByTerms(masked, extractProtectedTermsFromKey(key), tokens);
        masked = protectByPattern(masked, COLOR_CODE_PATTERN, tokens);
        masked = protectByPattern(masked, PLACEHOLDER_PATTERN, tokens);
        masked = protectByPattern(masked, BRACE_PATTERN, tokens);
        masked = protectByPattern(masked, IDENTIFIER_PATTERN, tokens);
        masked = protectByPattern(masked, PAREN_PATTERN, tokens);

        return new ProtectResult(masked, tokens);
    }

    private static String protectByPattern(String input, Pattern pattern, List<String> tokens) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String original = matcher.group();
            String marker = "__NO_TR_" + tokens.size() + "__";
            tokens.add(original);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(marker));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String protectByTerms(String input, Set<String> terms, List<String> tokens) {
        String out = input;
        for (String term : terms) {
            Pattern p = Pattern.compile("(?i)\\b" + Pattern.quote(term) + "\\b");
            Matcher matcher = p.matcher(out);
            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                String original = matcher.group();
                String marker = "__NO_TR_" + tokens.size() + "__";
                tokens.add(original);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(marker));
            }
            matcher.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }

    private static Set<String> extractProtectedTermsFromKey(String key) {
        Set<String> terms = new LinkedHashSet<>();
        if (key == null || key.isBlank()) {
            return terms;
        }

        String[] parts = key.toLowerCase().split("\\.");
        if (parts.length >= 2) {
            String namespace = parts[1];
            if (namespace.matches("[a-z0-9_\\-]{3,}")) {
                terms.add(namespace.replace('_', ' ').replace('-', ' ').trim());
                terms.add(namespace);
            }
        }

        // Intentionally avoid protecting the item id segment (e.g. lightning_sword),
        // because those words are usually translatable and over-protecting them lowers
        // translation quality (especially for es_mx output).
        return terms;
    }

    private static String restoreSegments(String translated, List<String> tokens) {
        String restored = translated;
        for (int i = 0; i < tokens.size(); i++) {
            restored = restored.replace("__NO_TR_" + i + "__", tokens.get(i));
        }
        return restored;
    }

    private static String normalizeTranslatedValue(String key, String text, String targetLangCode) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String normalized = text.trim();
        if (targetLangCode != null && targetLangCode.startsWith("es_")) {
            normalized = normalizeEnglishLeftoversForSpanish(normalized);
            String canonical = canonicalTermFromKey(key, normalized);
            if (canonical != null) {
                normalized = enforceCanonicalLeadingNoun(normalized, canonical);
            }
        }

        if ("es_mx".equalsIgnoreCase(targetLangCode)) {
            normalized = normalizeForMexicanSpanish(normalized);
        }

        return normalized;
    }

    private static String normalizeForMexicanSpanish(String text) {
        String out = text;
        out = out.replace("ordenador", "computadora");
        out = out.replace("Ordenador", "Computadora");
        out = out.replace("móvil", "celular");
        out = out.replace("Móvil", "Celular");
        out = out.replace("patata", "papa");
        out = out.replace("Patata", "Papa");
        out = out.replace("zumo", "jugo");
        out = out.replace("Zumo", "Jugo");
        return out;
    }

    private static String normalizeEnglishLeftoversForSpanish(String text) {
        String out = text;

        out = replaceWholeWordCaseInsensitive(out, "gold", "oro");
        out = replaceWholeWordCaseInsensitive(out, "iron", "hierro");
        out = replaceWholeWordCaseInsensitive(out, "diamond", "diamante");
        out = replaceWholeWordCaseInsensitive(out, "netherite", "netherita");
        out = replaceWholeWordCaseInsensitive(out, "wood", "madera");
        out = replaceWholeWordCaseInsensitive(out, "wooden", "de madera");
        out = replaceWholeWordCaseInsensitive(out, "stone", "piedra");

        out = replaceWholeWordCaseInsensitive(out, "sword", "espada");
        out = replaceWholeWordCaseInsensitive(out, "bow", "arco");
        out = replaceWholeWordCaseInsensitive(out, "helmet", "casco");
        out = replaceWholeWordCaseInsensitive(out, "chestplate", "pechera");
        out = replaceWholeWordCaseInsensitive(out, "leggings", "grebas");
        out = replaceWholeWordCaseInsensitive(out, "boots", "botas");
        out = replaceWholeWordCaseInsensitive(out, "pickaxe", "pico");
        out = replaceWholeWordCaseInsensitive(out, "axe", "hacha");
        out = replaceWholeWordCaseInsensitive(out, "shovel", "pala");
        out = replaceWholeWordCaseInsensitive(out, "hoe", "azada");
        out = replaceWholeWordCaseInsensitive(out, "claw", "garra");

        out = out.replaceAll("\\s+", " ").trim();
        return out;
    }

    private static String replaceWholeWordCaseInsensitive(String text, String sourceWord, String replacement) {
        return Pattern.compile("(?i)\\b" + Pattern.quote(sourceWord) + "\\b")
                .matcher(text)
                .replaceAll(Matcher.quoteReplacement(replacement));
    }

    private static String canonicalTermFromKey(String key, String translatedText) {
        if (key == null) {
            return null;
        }

        String lowerKey = key.toLowerCase();
        boolean leatherLike = isLeatherLike(lowerKey, translatedText);

        if (lowerKey.contains("sword")) return "espada";
        if (lowerKey.contains("pickaxe")) return "pico";
        if (lowerKey.contains("axe")) return "hacha";
        if (lowerKey.contains("shovel")) return "pala";
        if (lowerKey.contains("hoe")) return "azada";
        if (lowerKey.contains("bow")) return "arco";
        if (lowerKey.contains("claw")) return "garra";
        if (lowerKey.contains("helmet")) return "casco";
        if (lowerKey.contains("hat") || lowerKey.contains("cap")) return "gorra";
        if (lowerKey.contains("chestplate") || lowerKey.contains("tunic") || lowerKey.contains("robe")) {
            return leatherLike ? "tunica" : "pechera";
        }
        if (lowerKey.contains("leggings") || lowerKey.contains("pants") || lowerKey.contains("trousers")) {
            return leatherLike ? "pantalones" : "grebas";
        }
        if (lowerKey.contains("boots")) return "botas";

        return null;
    }

    private static boolean isLeatherLike(String lowerKey, String translatedText) {
        String lowerText = translatedText == null ? "" : translatedText.toLowerCase();
        return lowerKey.contains("leather") || lowerKey.contains("cloth") || lowerKey.contains("robe") ||
                lowerText.contains("cuero") || lowerText.contains("ropa") || lowerText.contains("tunica");
    }

    private static String enforceCanonicalLeadingNoun(String text, String noun) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return text;
        }

        String displayNoun = Character.isUpperCase(trimmed.charAt(0))
                ? capitalizeFirst(noun)
                : noun;

        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith(noun + " ") || lower.equals(noun) || lower.startsWith(noun + " de ")) {
            return displayNoun + trimmed.substring(noun.length());
        }
        if (lower.startsWith(displayNoun.toLowerCase(Locale.ROOT) + " ") ||
                lower.equals(displayNoun.toLowerCase(Locale.ROOT)) ||
                lower.startsWith(displayNoun.toLowerCase(Locale.ROOT) + " de ")) {
            return trimmed;
        }

        WordMatch match = findFirstCanonicalWordMatch(trimmed, noun);
        if (match != null) {
            String before = trimmed.substring(0, match.start).trim();
            String after = trimmed.substring(match.end).trim();
            String afterCore = stripLeadingDe(after);

            StringBuilder tail = new StringBuilder();
            if (!before.isEmpty()) {
                tail.append(before);
            }
            if (!afterCore.isEmpty()) {
                if (tail.length() > 0) tail.append(" ");
                tail.append(afterCore);
            }

            if (tail.length() == 0) {
                return displayNoun;
            }
            return cleanupSpanishJoin(displayNoun + " de " + tail);
        }

        return cleanupSpanishJoin(displayNoun + " de " + trimmed);
    }

    private static boolean containsWord(String text, String word) {
        return Pattern.compile("(?i)\\b" + Pattern.quote(word) + "\\b").matcher(text).find();
    }

    private static String stripLeadingDe(String text) {
        String out = text.trim();
        if (out.toLowerCase(Locale.ROOT).startsWith("de ")) {
            return out.substring(3).trim();
        }
        return out;
    }

    private static String cleanupSpanishJoin(String text) {
        String out = text.replaceAll("\\s+", " ").trim();
        out = out.replaceAll("(?i)\\bde\\s+de\\b", "de");
        return out;
    }

    private static WordMatch findFirstCanonicalWordMatch(String text, String noun) {
        List<String> aliases = new ArrayList<>();
        aliases.add(noun);
        if ("casco".equals(noun)) {
            aliases.add("yelmo");
        }

        WordMatch best = null;
        for (String alias : aliases) {
            Matcher matcher = Pattern.compile("(?i)\\b" + Pattern.quote(alias) + "\\b").matcher(text);
            if (matcher.find()) {
                WordMatch candidate = new WordMatch(matcher.start(), matcher.end());
                if (best == null || candidate.start < best.start) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static String capitalizeFirst(String text) {
        if (text == null || text.isEmpty()) return text;
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static class WordMatch {
        private final int start;
        private final int end;

        private WordMatch(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private void log(String message) {
        String line = "[" + LocalDateTime.now().format(LOG_TS_FORMAT) + "] " + message;
        logArea.append(line + System.lineSeparator());
        logArea.setCaretPosition(logArea.getDocument().getLength());
        appendToFileLog(line);
    }

    private void logException(String context, Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        pw.flush();

        log(context + ": " + throwable.getMessage());
        appendToFileLog(sw.toString());
    }

    private synchronized void appendToFileLog(String line) {
        try {
            Path parent = fileLogPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    fileLogPath,
                    line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
            // Keep UI responsive even if disk logging fails.
        }
    }

    private static class ProtectResult {
        private final String maskedText;
        private final List<String> tokens;

        private ProtectResult(String maskedText, List<String> tokens) {
            this.maskedText = maskedText;
            this.tokens = tokens;
        }
    }

    private static class Translator {
        private static final int CHUNK_SIZE = 25;
        private static final int MAX_CONSECUTIVE_ERRORS = 10;

        private final Gson gson = new Gson();
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        private final List<String> endpoints;
        private final String apiKey;
        private final String apiMode;
        private final java.util.function.Consumer<String> logger;
        private final BooleanSupplier shouldCancel;
        private final Map<String, String> cache = new HashMap<>();
        private int currentEndpointIndex = 0;
        private int consecutiveErrors = 0;

        private Translator(List<String> endpoints, String apiKey, String apiMode, java.util.function.Consumer<String> logger, BooleanSupplier shouldCancel) {
            this.endpoints = new ArrayList<>(endpoints);
            this.apiKey = apiKey;
            this.apiMode = apiMode;
            this.logger = logger;
            this.shouldCancel = shouldCancel;
        }

        private String currentEndpoint() {
            return endpoints.get(currentEndpointIndex % endpoints.size());
        }

        private void rotateEndpoint(String reason) {
            String old = currentEndpoint();
            currentEndpointIndex++;
            if (currentEndpointIndex < endpoints.size()) {
                logger.accept("[POOL] " + reason + " on " + old + ". Switching to: " + currentEndpoint());
            }
        }

        private void registerError(int statusCode, String context) throws InterruptedException, ApiBlockedException {
            consecutiveErrors++;
            if (statusCode == 429) {
                long waitMs = (long) Math.pow(2, Math.min(consecutiveErrors, 6)) * 1000L;
                logger.accept("[429] Rate limited. Backoff " + (waitMs / 1000) + "s, then rotating endpoint. (error #" + consecutiveErrors + ")");
                sleepChecked(waitMs);
                rotateEndpoint("rate limited");
            } else if (statusCode == 403) {
                rotateEndpoint("forbidden");
            }
            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS || currentEndpointIndex >= endpoints.size()) {
                throw new ApiBlockedException(
                        "All " + endpoints.size() + " endpoint(s) returned errors after " +
                        consecutiveErrors + " consecutive failures.\n" +
                        "Check endpoints, API key, or try again later.");
            }
        }

        private void resetErrors() {
            consecutiveErrors = 0;
        }

        private void sleepChecked(long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < end) {
                checkCancellation();
                Thread.sleep(Math.min(500, end - System.currentTimeMillis()));
            }
        }

        private Map<String, String> translateBatch(List<String> texts, String source, String target) throws InterruptedException, ApiBlockedException {
            checkCancellation();
            Map<String, String> result = new HashMap<>();
            List<String> toTranslate = new ArrayList<>();

            for (String text : texts) {
                if (text == null || text.isBlank()) {
                    result.put(text, text);
                    continue;
                }
                if (cache.containsKey(text)) {
                    result.put(text, cache.get(text));
                } else {
                    toTranslate.add(text);
                }
            }

            for (int i = 0; i < toTranslate.size(); i += CHUNK_SIZE) {
                checkCancellation();
                int end = Math.min(i + CHUNK_SIZE, toTranslate.size());
                List<String> chunk = toTranslate.subList(i, end);
                Map<String, String> translatedChunk = translateChunk(chunk, source, target);
                result.putAll(translatedChunk);
            }

            for (String text : texts) {
                result.putIfAbsent(text, text);
            }

            return result;
        }

        private Map<String, String> translateChunk(List<String> chunk, String source, String target) throws InterruptedException, ApiBlockedException {
            checkCancellation();
            if ("Google Cloud".equals(apiMode)) {
                return translateChunkGoogle(chunk, source, target);
            }
            if ("MyMemory (Gratis)".equals(apiMode)) {
                return translateChunkMyMemory(chunk, source, target);
            }
            return translateChunkLibre(chunk, source, target);
        }

        private Map<String, String> translateChunkGoogle(List<String> chunk, String source, String target) throws InterruptedException, ApiBlockedException {
            Map<String, String> translated = new HashMap<>();
            checkCancellation();

            JsonObject req = new JsonObject();
            JsonArray q = new JsonArray();
            for (String item : chunk) q.add(item);
            req.add("q", q);
            req.addProperty("source", source);
            req.addProperty("target", target);

            String url = GOOGLE_ENDPOINT + "?key=" + apiKey;

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(req), StandardCharsets.UTF_8))
                    .build();

            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                checkCancellation();
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    logger.accept("[GOOGLE] API error " + status + ": " + response.body() + ". Falling back to single requests.");
                    registerError(status, "google batch");
                    return fallbackSingle(chunk, source, target);
                }

                List<String> batchResponse = parseGoogleTranslatedList(response.body());
                if (batchResponse.size() != chunk.size()) {
                    logger.accept("[GOOGLE] Response size mismatch. Falling back to single requests.");
                    return fallbackSingle(chunk, source, target);
                }

                resetErrors();
                for (int i = 0; i < chunk.size(); i++) {
                    String original = chunk.get(i);
                    String value = batchResponse.get(i);
                    if (value == null || value.isBlank()) value = original;
                    cache.put(original, value);
                    translated.put(original, value);
                }
                return translated;
            } catch (IOException e) {
                logger.accept("[GOOGLE] Request failed: " + e.getMessage() + ". Falling back to single.");
                return fallbackSingle(chunk, source, target);
            }
        }

        private String convertLangCodeToGoogle(String langCode) {
            return langCode.replace('_', '-');
        }

        private List<String> parseGoogleTranslatedList(String responseBody) {
            List<String> out = new ArrayList<>();
            try {
                JsonObject data = JsonParser.parseString(responseBody).getAsJsonObject()
                        .getAsJsonObject("data");
                JsonArray translations = data.getAsJsonArray("translations");
                if (translations != null) {
                    for (JsonElement el : translations) {
                        out.add(el.getAsJsonObject().get("translatedText").getAsString());
                    }
                }
            } catch (Exception ignored) {}
            return out;
        }

        private Map<String, String> translateChunkLibre(List<String> chunk, String source, String target) throws InterruptedException, ApiBlockedException {
            Map<String, String> translated = new HashMap<>();

            JsonObject req = new JsonObject();
            JsonArray q = new JsonArray();
            for (String item : chunk) q.add(item);
            req.add("q", q);
            req.addProperty("source", source);
            req.addProperty("target", target);
            req.addProperty("format", "text");
            if (!apiKey.isBlank()) req.addProperty("api_key", apiKey);

            HttpRequest request = HttpRequest.newBuilder(URI.create(currentEndpoint()))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(req), StandardCharsets.UTF_8))
                    .build();

            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                checkCancellation();
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    logger.accept("[BATCH] API error " + status + " on " + currentEndpoint() + ". Falling back to single requests.");
                    registerError(status, "batch");
                    return fallbackSingle(chunk, source, target);
                }

                List<String> batchResponse = parseTranslatedList(response.body());
                if (batchResponse.size() != chunk.size()) {
                    logger.accept("[BATCH] Response size mismatch. Falling back to single requests.");
                    return fallbackSingle(chunk, source, target);
                }

                resetErrors();
                for (int i = 0; i < chunk.size(); i++) {
                    String original = chunk.get(i);
                    String value = batchResponse.get(i);
                    if (value == null || value.isBlank()) value = original;
                    cache.put(original, value);
                    translated.put(original, value);
                }
                return translated;
            } catch (IOException e) {
                logger.accept("[BATCH] Request failed: " + e.getMessage() + ". Falling back to single requests.");
                return fallbackSingle(chunk, source, target);
            }
        }

        private Map<String, String> fallbackSingle(List<String> chunk, String source, String target) throws InterruptedException, ApiBlockedException {
            Map<String, String> translated = new HashMap<>();
            for (String item : chunk) {
                checkCancellation();
                translated.put(item, translate(item, source, target));
            }
            return translated;
        }

        private Map<String, String> translateChunkMyMemory(List<String> chunk, String source, String target) throws InterruptedException, ApiBlockedException {
            Map<String, String> translated = new HashMap<>();
            for (String item : chunk) {
                checkCancellation();
                if (item == null || item.isBlank()) {
                    translated.put(item, item);
                    continue;
                }
                if (cache.containsKey(item)) {
                    translated.put(item, cache.get(item));
                    continue;
                }
                String result = translateWithMyMemory(item, source, target);
                if (result == null || result.isBlank()) result = item;
                cache.put(item, result);
                translated.put(item, result);
                // MyMemory recomienda no saturar: pequeña pausa entre peticiones
                Thread.sleep(200);
            }
            return translated;
        }

        private String translateWithMyMemory(String text, String source, String target) throws InterruptedException, ApiBlockedException {
            checkCancellation();
            try {
                String langpair = source + "|" + target;
                String encodedText = java.net.URLEncoder.encode(text, StandardCharsets.UTF_8);
                String urlStr = MY_MEMORY_ENDPOINT + "?q=" + encodedText + "&langpair=" + langpair;
                if (!apiKey.isBlank()) {
                    urlStr += "&de=" + java.net.URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
                }

                HttpRequest request = HttpRequest.newBuilder(URI.create(urlStr))
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                checkCancellation();
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    logger.accept("[MyMemory] Error HTTP " + status);
                    registerError(status, "mymemory");
                    return null;
                }

                resetErrors();
                return parseMyMemoryResponse(response.body());
            } catch (IOException e) {
                logger.accept("[MyMemory] Request failed: " + e.getMessage());
                return null;
            }
        }

        private String parseMyMemoryResponse(String responseBody) {
            try {
                JsonObject obj = JsonParser.parseString(responseBody).getAsJsonObject();
                JsonObject responseData = obj.getAsJsonObject("responseData");
                if (responseData != null && responseData.has("translatedText")) {
                    String translated = responseData.get("translatedText").getAsString();
                    if (translated != null && !translated.isBlank()) {
                        return translated;
                    }
                }
            } catch (Exception ignored) {}
            return null;
        }

        private String translate(String text, String source, String target) throws InterruptedException, ApiBlockedException {
            checkCancellation();
            if (text.isBlank()) return text;
            if (cache.containsKey(text)) return cache.get(text);

            try {
                String result;
                if ("Google Cloud".equals(apiMode)) {
                    result = translateWithGoogle(text, source, target);
                } else if ("MyMemory (Gratis)".equals(apiMode)) {
                    result = translateWithMyMemory(text, source, target);
                } else {
                    result = translateWithLibreTranslate(text, source, target);
                }
                
                if (result == null || result.isBlank()) result = text;
                cache.put(text, result);
                return result;
            } catch (Exception e) {
                logger.accept("[ERROR] Translation failed: " + e.getMessage() + ", preserving original.");
                registerError(0, "single");
                cache.put(text, text);
                return text;
            }
        }

        private String translateWithGoogle(String text, String source, String target) throws Exception {
            checkCancellation();
            JsonObject req = new JsonObject();
            req.addProperty("q", text);
            req.addProperty("source", source);
            req.addProperty("target", target);

            String url = GOOGLE_ENDPOINT + "?key=" + apiKey;

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(45))
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(req), StandardCharsets.UTF_8))
                    .build();

            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                checkCancellation();
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    logger.accept("[GOOGLE] API error " + status);
                    registerError(status, "single google");
                    return null;
                }

                resetErrors();
                return parseGoogleTranslatedText(response.body());
            } catch (IOException e) {
                logger.accept("[GOOGLE] Request failed: " + e.getMessage());
                return null;
            }
        }

        private String translateWithLibreTranslate(String text, String source, String target) throws InterruptedException, ApiBlockedException {
            JsonObject req = new JsonObject();
            req.addProperty("q", text);
            req.addProperty("source", source);
            req.addProperty("target", target);
            req.addProperty("format", "text");

            String url = currentEndpoint();
            if (!apiKey.isBlank()) req.addProperty("api_key", apiKey);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(45))
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(req), StandardCharsets.UTF_8))
                    .build();

            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                checkCancellation();
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    logger.accept("[" + status + "] Error on " + currentEndpoint() + ", preserving original.");
                    registerError(status, "single");
                    return null;
                }

                resetErrors();
                return parseTranslatedText(response.body());
            } catch (IOException e) {
                logger.accept("[IO] Request failed: " + e.getMessage());
                return null;
            }
        }

        private void checkCancellation() throws InterruptedException {
            if (shouldCancel.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Translation cancelled by user.");
            }
        }

        // Google Cloud Translation API response: {"data":{"translations":[{"translatedText":"..."}]}}
        private String parseGoogleTranslatedText(String responseBody) {
            try {
                JsonObject data = JsonParser.parseString(responseBody).getAsJsonObject()
                        .getAsJsonObject("data");
                JsonArray translations = data.getAsJsonArray("translations");
                if (translations != null && !translations.isEmpty()) {
                    return translations.get(0).getAsJsonObject().get("translatedText").getAsString();
                }
            } catch (Exception ignored) {}
            return null;
        }

        private String parseTranslatedText(String responseBody) {
            JsonElement parsed = JsonParser.parseString(responseBody);
            if (parsed.isJsonObject()) {
                JsonObject obj = parsed.getAsJsonObject();
                if (obj.has("translatedText")) {
                    return obj.get("translatedText").getAsString();
                }
            }

            if (parsed.isJsonArray()) {
                JsonArray arr = parsed.getAsJsonArray();
                if (!arr.isEmpty() && arr.get(0).isJsonObject()) {
                    JsonObject first = arr.get(0).getAsJsonObject();
                    if (first.has("translatedText")) {
                        return first.get("translatedText").getAsString();
                    }
                }
            }

            return null;
        }

        private List<String> parseTranslatedList(String responseBody) {
            List<String> out = new ArrayList<>();
            JsonElement parsed = JsonParser.parseString(responseBody);

            if (parsed.isJsonObject()) {
                JsonObject obj = parsed.getAsJsonObject();
                if (obj.has("translatedText")) {
                    JsonElement translated = obj.get("translatedText");
                    if (translated.isJsonArray()) {
                        JsonArray arr = translated.getAsJsonArray();
                        for (JsonElement item : arr) {
                            out.add(item.getAsString());
                        }
                        return out;
                    }
                    out.add(translated.getAsString());
                    return out;
                }
            }

            if (parsed.isJsonArray()) {
                JsonArray arr = parsed.getAsJsonArray();
                for (JsonElement item : arr) {
                    if (item.isJsonObject() && item.getAsJsonObject().has("translatedText")) {
                        out.add(item.getAsJsonObject().get("translatedText").getAsString());
                    } else {
                        out.add(item.getAsString());
                    }
                }
            }

            return out;
        }
    }

    private static class NoLangFilesException extends Exception {
        private NoLangFilesException(String message) { super(message); }
    }

    private static class ApiBlockedException extends Exception {
        private ApiBlockedException(String message) { super(message); }
    }
}
