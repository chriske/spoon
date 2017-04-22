package com.squareup.spoon;

import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.testrunner.IRemoteAndroidTestRunner;
import com.android.ddmlib.testrunner.ITestRunListener;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.squareup.spoon.html.HtmlRenderer;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.commons.io.FileUtils;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.squareup.spoon.DeviceTestResult.Status;
import static com.squareup.spoon.SpoonInstrumentationInfo.parseFromFile;
import static com.squareup.spoon.SpoonLogger.logDebug;
import static com.squareup.spoon.SpoonLogger.logInfo;
import static java.util.Collections.synchronizedSet;

/** Represents a collection of devices and the test configuration to be executed. */
public final class SpoonRunner {
  private static final String DEFAULT_TITLE = "Spoon Execution";
  public static final String DEFAULT_OUTPUT_DIRECTORY = "spoon-output";
  private static final Duration DEFAULT_ADB_TIMEOUT = Duration.ofMinutes(10);
  private final ExecutorService threadExecutor;

  private final String title;
  private final File androidSdk;
  private final File applicationApk;
  private final File instrumentationApk;
  private final File output;
  private final boolean debug;
  private final boolean noAnimations;
  private final Duration adbTimeout;
  private final List<String> instrumentationArgs;
  private final String className;
  private final String methodName;
  private final Set<String> serials;
  private final Set<String> skipDevices;
  private final boolean shard;
  private final String classpath;
  private final IRemoteAndroidTestRunner.TestSize testSize;
  private boolean codeCoverage;
  private final boolean failIfNoDeviceConnected;
  private final List<ITestRunListener> testRunListeners;
  private final boolean terminateAdb;
  private File initScript;
  private final boolean grantAll;

  private SpoonRunner(String title, File androidSdk, File applicationApk, File instrumentationApk,
      File output, boolean debug, boolean noAnimations, Duration adbTimeout, Set<String> serials,
      Set<String> skipDevices, boolean shard, String classpath, List<String> instrumentationArgs,
      String className, String methodName, IRemoteAndroidTestRunner.TestSize testSize,
      boolean failIfNoDeviceConnected, List<ITestRunListener> testRunListeners, boolean sequential,
      File initScript, boolean grantAll, boolean terminateAdb, boolean codeCoverage) {
    this.title = title;
    this.androidSdk = androidSdk;
    this.applicationApk = applicationApk;
    this.instrumentationApk = instrumentationApk;
    this.output = output;
    this.debug = debug;
    this.noAnimations = noAnimations;
    this.adbTimeout = adbTimeout;
    this.instrumentationArgs = instrumentationArgs;
    this.className = className;
    this.methodName = methodName;
    this.classpath = classpath;
    this.testSize = testSize;
    this.skipDevices = skipDevices;
    this.codeCoverage = codeCoverage;
    this.serials = ImmutableSet.copyOf(serials);
    this.shard = shard;
    this.failIfNoDeviceConnected = failIfNoDeviceConnected;
    this.testRunListeners = testRunListeners;
    this.terminateAdb = terminateAdb;
    this.initScript = initScript;
    this.grantAll = grantAll;

    if (sequential) {
      this.threadExecutor = Executors.newSingleThreadExecutor();
    } else {
      this.threadExecutor = Executors.newCachedThreadPool();
    }
  }

  /**
   * Install and execute the tests on all specified devices.
   *
   * @return {@code true} if there were no test failures or exceptions thrown.
   */
  public boolean run() {
    checkArgument(applicationApk.exists(), "Could not find application APK.");
    checkArgument(instrumentationApk.exists(), "Could not find instrumentation APK.");

    AndroidDebugBridge adb = SpoonUtils.initAdb(androidSdk, adbTimeout);

    try {
      final SpoonInstrumentationInfo testInfo = parseFromFile(instrumentationApk);

      // If we were given an empty serial set, load all available devices.
      Set<String> serials = this.serials;
      if (serials.isEmpty()) {
        serials = SpoonUtils.findAllDevices(adb, testInfo.getMinSdkVersion());
      }
      if (this.skipDevices != null && !this.skipDevices.isEmpty()) {
        serials.removeAll(this.skipDevices);
      }
      if (failIfNoDeviceConnected && serials.isEmpty()) {
        throw new RuntimeException("No device(s) found.");
      }

      // Execute all the things...
      SpoonSummary summary = runTests(adb, serials, testInfo);
      // ...and render to HTML
      new HtmlRenderer(summary, SpoonUtils.GSON, output).render();
      if (codeCoverage) {
        try {
          SpoonCoverageMerger.mergeCoverageFiles(serials, output);
          logDebug(debug, "Merging of coverage files done.");
        } catch (IOException exception) {
          throw new RuntimeException("Error while merging coverage files. "
              + "Did you set the \"testCoverageEnabled\" flag in your build.gradle?", exception);
        }
      }

      return parseOverallSuccess(summary);
    } finally {
      if (terminateAdb) {
        AndroidDebugBridge.terminate();
      }
    }
  }

  private SpoonSummary runTests(AndroidDebugBridge adb, Set<String> serials,
      final SpoonInstrumentationInfo testInfo) {
    int targetCount = serials.size();
    logInfo("Executing instrumentation suite on %d device(s).", targetCount);

    try {
      FileUtils.deleteDirectory(output);
    } catch (IOException e) {
      throw new RuntimeException("Unable to clean output directory: " + output, e);
    }

    logDebug(debug, "Application: %s from %s", testInfo.getApplicationPackage(),
        applicationApk.getAbsolutePath());
    logDebug(debug, "Instrumentation: %s from %s", testInfo.getInstrumentationPackage(),
        instrumentationApk.getAbsolutePath());

    final SpoonSummary.Builder summary = new SpoonSummary.Builder().setTitle(title).start();

    if (testSize != null) {
      summary.setTestSize(testSize);
    }

    executeInitScript();

    if (targetCount == 1) {
      // Since there is only one device just execute it synchronously in this process.
      String serial = Iterables.getOnlyElement(serials);
      String safeSerial = SpoonUtils.sanitizeSerial(serial);
      String currentLocale = "";
      try {
        logDebug(debug, "[%s] Starting execution.", serial);
        SpoonDeviceRunner testRunner = getTestRunner(serial, 0, 0, testInfo);
        testRunner.prepareDevice(adb);
        currentLocale = testRunner.changeLocaleAndReboot("en", "US");
        summary.addResult(new DeviceIdentifier(safeSerial, currentLocale), testRunner.run());
        currentLocale = testRunner.changeLocaleAndReboot("fr", "FR");
        summary.addResult(new DeviceIdentifier(safeSerial, currentLocale), testRunner.run());
        currentLocale = testRunner.changeLocaleAndReboot("hu", "HU");
        summary.addResult(new DeviceIdentifier(safeSerial, currentLocale), testRunner.run());
      } catch (Exception e) {
        logDebug(debug, "[%s] Execution exception!", serial);
        e.printStackTrace(System.out);
        summary.addResult(new DeviceIdentifier(safeSerial, currentLocale), new DeviceResult.Builder().addException(e).build());
      } finally {
        logDebug(debug, "[%s] Execution done.", serial);
      }
    } else {
      // Spawn a new thread for each device and wait for them all to finish.
      final CountDownLatch done = new CountDownLatch(targetCount);
      final Set<String> remaining = synchronizedSet(new HashSet<>(serials));

      int shardIndex = 0;
      final int numShards = shard ? serials.size() : 0;
      for (final String serial : serials) {
        final String safeSerial = SpoonUtils.sanitizeSerial(serial);
        logDebug(debug, "[%s] Starting execution.", serial);
        final int safeShardIndex = shardIndex;
        Runnable runnable = new Runnable() {
          @Override public void run() {
            try {
              //summary.addResult(safeSerial,
              //    getTestRunner(serial, safeShardIndex, numShards, testInfo).runInNewProcess());
            } catch (Exception e) {
              e.printStackTrace(System.out);
              //summary.addResult(safeSerial, new DeviceResult.Builder().addException(e).build());
            } finally {
              done.countDown();
              remaining.remove(serial);
              logDebug(debug, "[%s] Execution done. (%s remaining %s)", serial, done.getCount(),
                  remaining);
            }
          }
        };
        if (shard) {
          shardIndex++;
          logDebug(debug, "shardIndex [%d]", shardIndex);
        }
        threadExecutor.execute(runnable);
      }

      try {
        done.await();
        threadExecutor.shutdown();
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }

    if (!debug) {
      // Clean up anything in the work directory.
      try {
        FileUtils.deleteDirectory(new File(output, SpoonDeviceRunner.TEMP_DIR));
      } catch (IOException ignored) {
      }
    }

    return summary.end().build();
  }

  /** Execute the script file specified in param --init-script */
  private void executeInitScript() {
    if (initScript != null && initScript.exists()) {
      try {
        Runtime run = Runtime.getRuntime();
        Process proc = run.exec(new String[] {
            "/bin/bash", "-c", initScript.getAbsolutePath()
        });
        BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
        String line;

        logInfo("Output of running script is");
        while ((line = br.readLine()) != null) {
          logInfo(line);
        }
        proc.waitFor(); // Wait for the process to finish.
        logInfo("Script executed successfully %s", initScript.getAbsolutePath());
      } catch (IOException e) {
        logDebug(debug, "Error executing script for path: %s", initScript.getAbsolutePath());
        e.printStackTrace(System.out);
      } catch (InterruptedException e) {
        logDebug(debug, "Script execution interrupted for path: %s", initScript.getAbsolutePath());
        e.printStackTrace(System.out);
      }
    }
  }

  /** Returns {@code false} if a test failed on any device. */
  static boolean parseOverallSuccess(SpoonSummary summary) {
    for (DeviceResult result : summary.getResults().values()) {
      if (result.getInstallFailed()) {
        return false; // App and/or test installation failed.
      }
      if (!result.getExceptions().isEmpty() || result.getTestResults().isEmpty()) {
        return false; // Top-level exception present, or no tests were run.
      }
      for (DeviceTestResult methodResult : result.getTestResults().values()) {
        if (methodResult.getStatus() != Status.PASS) {
          return false; // Individual test failure.
        }
      }
    }
    return true;
  }

  private SpoonDeviceRunner getTestRunner(String serial, int shardIndex, int numShards,
      SpoonInstrumentationInfo testInfo) {
    return new SpoonDeviceRunner(androidSdk, applicationApk, instrumentationApk, output, serial,
        shardIndex, numShards, debug, noAnimations, adbTimeout, classpath, testInfo,
        instrumentationArgs, className, methodName, testSize, testRunListeners, codeCoverage,
        grantAll);
  }

  /** Build a test suite for the specified devices and configuration. */
  public static class Builder {
    private String title = DEFAULT_TITLE;
    private File androidSdk = cleanFile(System.getenv("ANDROID_HOME"));
    private File applicationApk;
    private File instrumentationApk;
    private File output = cleanFile(DEFAULT_OUTPUT_DIRECTORY);
    private boolean debug = false;
    private Set<String> serials = new LinkedHashSet<>();
    private Set<String> skipDevices = new LinkedHashSet<>();
    private String classpath = System.getProperty("java.class.path");
    private List<String> instrumentationArgs;
    private String className;
    private String methodName;
    private boolean noAnimations;
    private IRemoteAndroidTestRunner.TestSize testSize;
    private Duration adbTimeout = DEFAULT_ADB_TIMEOUT;
    private boolean failIfNoDeviceConnected;
    private List<ITestRunListener> testRunListeners = new ArrayList<>();
    private boolean sequential;
    private File initScript;
    private boolean grantAll;
    private boolean terminateAdb = true;
    private boolean codeCoverage;
    private boolean shard = false;

    /** Identifying title for this execution. */
    public Builder setTitle(String title) {
      checkNotNull(title, "Title cannot be null.");
      this.title = title;
      return this;
    }

    /** Path to the local Android SDK directory. */
    public Builder setAndroidSdk(File androidSdk) {
      checkNotNull(androidSdk, "SDK path not specified.");
      checkArgument(androidSdk.exists(), "SDK path does not exist.");
      this.androidSdk = androidSdk;
      return this;
    }

    /** Path to application APK. */
    public Builder setApplicationApk(File apk) {
      checkNotNull(apk, "APK path not specified.");
      checkArgument(apk.exists(), "APK path does not exist.");
      this.applicationApk = apk;
      return this;
    }

    /** Path to instrumentation APK. */
    public Builder setInstrumentationApk(File apk) {
      checkNotNull(apk, "Instrumentation APK path not specified.");
      checkArgument(apk.exists(), "Instrumentation APK path does not exist.");
      this.instrumentationApk = apk;
      return this;
    }

    /** Path to output directory. */
    public Builder setOutputDirectory(File output) {
      checkNotNull(output, "Output directory not specified.");
      this.output = output;
      return this;
    }

    /** Whether or not debug logging is enabled. */
    public Builder setDebug(boolean debug) {
      this.debug = debug;
      return this;
    }

    /** Whether or not animations are enabled. */
    public Builder setNoAnimations(boolean noAnimations) {
      this.noAnimations = noAnimations;
      return this;
    }

    /** Set ADB timeout. */
    public Builder setAdbTimeout(Duration value) {
      this.adbTimeout = value;
      return this;
    }

    /** Add a device serial for test execution. */
    public Builder addDevice(String serial) {
      checkNotNull(serial, "Serial cannot be null.");
      serials.add(serial);
      return this;
    }

    /** Add a device serial for skipping test execution. */
    public Builder skipDevice(String serial) {
      checkNotNull(serial, "Serial cannot be null.");
      skipDevices.add(serial);
      return this;
    }

    /** Classpath to use for new JVM processes. */
    public Builder setClasspath(String classpath) {
      checkNotNull(classpath, "Classpath cannot be null.");
      this.classpath = classpath;
      return this;
    }

    public Builder setInstrumentationArgs(List<String> instrumentationArgs) {
      this.instrumentationArgs = instrumentationArgs;
      return this;
    }

    public Builder setClassName(String className) {
      this.className = className;
      return this;
    }

    public Builder setTestSize(IRemoteAndroidTestRunner.TestSize testSize) {
      this.testSize = testSize;
      return this;
    }

    public Builder setFailIfNoDeviceConnected(boolean failIfNoDeviceConnected) {
      this.failIfNoDeviceConnected = failIfNoDeviceConnected;
      return this;
    }

    public Builder setSequential(boolean sequential) {
      this.sequential = sequential;
      return this;
    }

    public Builder setInitScript(File initScript) {
      if (initScript != null) {
        checkArgument(initScript.exists(),
            "Script path does not exist " + initScript.getAbsolutePath());
      }
      this.initScript = initScript;
      return this;
    }

    public Builder setGrantAll(boolean grantAll) {
      this.grantAll = grantAll;
      return this;
    }

    public Builder setMethodName(String methodName) {
      this.methodName = methodName;
      return this;
    }

    public Builder setCodeCoverage(boolean codeCoverage) {
      this.codeCoverage = codeCoverage;
      return this;
    }

    public Builder setShard(boolean shard) {
      this.shard = shard;
      return this;
    }

    public Builder addTestRunListener(ITestRunListener testRunListener) {
      checkNotNull(testRunListener, "TestRunListener cannot be null.");
      testRunListeners.add(testRunListener);
      return this;
    }

    public Builder setTerminateAdb(boolean terminateAdb) {
      this.terminateAdb = terminateAdb;
      return this;
    }

    public SpoonRunner build() {
      checkNotNull(androidSdk, "SDK is required.");
      checkArgument(androidSdk.exists(), "SDK path does not exist.");
      checkNotNull(applicationApk, "Application APK is required.");
      checkNotNull(instrumentationApk, "Instrumentation APK is required.");
      checkNotNull(serials, "Device serials are required.");
      if (!isNullOrEmpty(methodName)) {
        checkArgument(!isNullOrEmpty(className),
            "Must specify class name if you're specifying a method name.");
      }

      return new SpoonRunner(title, androidSdk, applicationApk, instrumentationApk, output, debug,
          noAnimations, adbTimeout, serials, skipDevices, shard, classpath,
          instrumentationArgs, className, methodName, testSize, failIfNoDeviceConnected,
          testRunListeners, sequential, initScript, grantAll, terminateAdb, codeCoverage);
    }
  }

  private static File cleanFile(String path) {
    if (path == null) {
      return null;
    }
    return new File(path);
  }
}
