import io.methvin.watcher.DirectoryWatcher;
import io.methvin.watcher.DirectoryChangeEvent;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;


public final class StepTrackerDevLauncher {
    private static final Path PROJECT = Path.of("").toAbsolutePath();
    private static final Path SOURCES = PROJECT.resolve("src/main/java");
    private static final Path OUTPUT = PROJECT.resolve("target/dev-classes");
    private static final String ORIGINAL_CLASSPATH = System.getProperty("java.class.path");
    private static final AtomicReference<Process> APP = new AtomicReference<>();
    private static final AtomicReference<ScheduledFuture<?>> PENDING_RELOAD = new AtomicReference<>();
    private static final ScheduledExecutorService RELOADER = Executors.newSingleThreadScheduledExecutor();

    private StepTrackerDevLauncher() {}

    public static void main(String[] args) throws Exception {
        if (!Files.isDirectory(SOURCES)) throw new IllegalStateException("Run StepTrackerDevLauncher from the project root");
        if (!compile()) throw new IllegalStateException("Initial compile failed; fix the Java errors and relaunch this launcher");
        startApp();

        DirectoryWatcher watcher = DirectoryWatcher.builder()
                .path(SOURCES)
                .listener(event -> onFileChange(event))
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process process = APP.getAndSet(null);
            if (process != null) process.destroy();
            RELOADER.shutdownNow();
            try { watcher.close(); } catch (IOException ignored) { }
        }));
        System.out.println("Watching src/main/java; saving a Java file recompiles and reloads the app.");
        watcher.watch();
    }

    private static void onFileChange(DirectoryChangeEvent event) {
        if (!event.path().toString().endsWith(".java")) return;
        ScheduledFuture<?> previous = PENDING_RELOAD.getAndSet(RELOADER.schedule(() -> {
            System.out.println("Java source changed; compiling...");
            if (compile()) {
                stopApp();
                startApp();
                System.out.println("App reloaded.");
            } else {
                System.err.println("Compile failed; previous app remains running.");
            }
        }, 500, TimeUnit.MILLISECONDS));
        if (previous != null) previous.cancel(false);
    }

    private static boolean compile() {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("Run this launcher with a JDK, not a JRE");
        try {
            Files.createDirectories(OUTPUT);
            List<String> arguments = new ArrayList<>(List.of(
                    "--add-modules", "jdk.httpserver", "-classpath", ORIGINAL_CLASSPATH, "-d", OUTPUT.toString()));
            try (Stream<Path> files = Files.walk(SOURCES)) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> arguments.add(path.toString()));
            }
            return compiler.run(null, System.out, System.err, arguments.toArray(String[]::new)) == 0;
        } catch (IOException e) {
            System.err.println("Could not compile project: " + e.getMessage());
            return false;
        }
    }

    private static void startApp() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = OUTPUT + System.getProperty("path.separator") + ORIGINAL_CLASSPATH;
        try {
            APP.set(new ProcessBuilder(java, "--add-modules", "jdk.httpserver", "-cp", classpath, "StepTrackerApp")
                    .inheritIO().start());
        } catch (IOException e) {
            throw new IllegalStateException("Could not launch StepTrackerApp", e);
        }
    }

    private static void stopApp() {
        Process process = APP.getAndSet(null);
        if (process == null) return;
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
