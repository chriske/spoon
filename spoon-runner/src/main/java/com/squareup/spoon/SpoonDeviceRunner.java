package com.squareup.spoon;

import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.CollectingOutputReceiver;
import com.android.ddmlib.DdmPreferences;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.InstallException;
import com.android.ddmlib.logcat.LogCatMessage;
import com.android.ddmlib.testrunner.IRemoteAndroidTestRunner;
import com.android.ddmlib.testrunner.ITestRunListener;
import com.android.ddmlib.testrunner.RemoteAndroidTestRunner;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;

import static com.android.ddmlib.FileListingService.FileEntry;
import static com.android.ddmlib.SyncService.getNullProgressMonitor;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.squareup.spoon.SpoonLogger.logDebug;
import static com.squareup.spoon.SpoonLogger.logError;
import static com.squareup.spoon.SpoonLogger.logInfo;
import static com.squareup.spoon.SpoonUtils.GSON;
import static com.squareup.spoon.SpoonUtils.createAnimatedGif;
import static com.squareup.spoon.SpoonUtils.obtainDirectoryFileEntry;
import static com.squareup.spoon.SpoonUtils.obtainRealDevice;
import static com.squareup.spoon.internal.Constants.SPOON_FILES;
import static com.squareup.spoon.internal.Constants.SPOON_SCREENSHOTS;

/** Represents a single device and the test configuration to be executed. */
public final class SpoonDeviceRunner {
  private static final String FILE_EXECUTION = "execution.json";
  private static final String FILE_RESULT = "result.json";
  private static final String SPOON_LOCALE_SWITCHER_DEBUG_APK = "spoon-locale-switcher-debug.apk";

  private static final String DEVICE_SCREENSHOT_DIR = "app_" + SPOON_SCREENSHOTS;
  private static final String DEVICE_FILE_DIR = "app_" + SPOON_FILES;
  private static final String[] DEVICE_DIRS = {DEVICE_SCREENSHOT_DIR, DEVICE_FILE_DIR};
  static final String TEMP_DIR = "work";
  static final String JUNIT_DIR = "junit-reports";
  static final String IMAGE_DIR = "image";
  static final String FILE_DIR = "file";
  static final String COVERAGE_FILE = "coverage.ec";
  static final String COVERAGE_DIR = "coverage";


  private final File sdk;
  private final File apk;
  private final File testApk;
  private final File output;
  private final DeviceIdentifier deviceIdentifier;
  private final int shardIndex;
  private final int numShards;
  private final boolean debug;
  private final boolean noAnimations;
  private final Duration adbTimeout;
  private final List<String> instrumentationArgs;
  private final String className;
  private final String methodName;
  private final IRemoteAndroidTestRunner.TestSize testSize;
  private File work;
  private File junitReport;
  private File imageDir;
  private File coverageDir;
  private File fileDir;
  private final String classpath;
  private final SpoonInstrumentationInfo instrumentationInfo;
  private boolean codeCoverage;
  private final List<ITestRunListener> testRunListeners;
  private final boolean grantAll;

  private transient IDevice device;

  /**
   * Create a test runner for a single device.
   *
   * @param sdk Path to the local Android SDK directory.
   * @param apk Path to application APK.
   * @param testApk Path to test application APK.
   * @param output Path to output directory.
   * @param deviceIdentifier Device to run the test on. (serial + locale)
   * @param debug Whether or not debug logging is enabled.
   * @param adbTimeout time in ms for longest test execution
   * @param classpath Custom JVM classpath or {@code null}.
   * @param instrumentationInfo Test apk manifest information.
   * @param className Test class name to run or {@code null} to run all tests.
   * @param methodName Test method name to run or {@code null} to run all tests.  Must also pass
   * {@code className}.
   * @param testRunListeners Additional TestRunListener or empty list.
   */
  SpoonDeviceRunner(File sdk, File apk, File testApk, File output, DeviceIdentifier deviceIdentifier, int shardIndex,
      int numShards, boolean debug, boolean noAnimations, Duration adbTimeout, String classpath,
      SpoonInstrumentationInfo instrumentationInfo, List<String> instrumentationArgs,
      String className, String methodName, IRemoteAndroidTestRunner.TestSize testSize,
      List<ITestRunListener> testRunListeners, boolean codeCoverage, boolean grantAll) {
    this.sdk = sdk;
    this.apk = apk;
    this.testApk = testApk;
    this.output = output;
    this.deviceIdentifier = deviceIdentifier;
    this.shardIndex = shardIndex;
    this.numShards = numShards;
    this.debug = debug;
    this.noAnimations = noAnimations;
    this.adbTimeout = adbTimeout;
    this.instrumentationArgs = instrumentationArgs;
    this.className = className;
    this.methodName = methodName;
    this.testSize = testSize;
    this.classpath = classpath;
    this.instrumentationInfo = instrumentationInfo;
    this.codeCoverage = codeCoverage;
    this.work = FileUtils.getFile(output, TEMP_DIR, SpoonUtils.sanitizeSerial(deviceIdentifier.getSerial()));
    this.testRunListeners = testRunListeners;
    this.grantAll = grantAll;
  }

  /** Serialize to disk and start {@link #main(String...)} in another process. */
  public DeviceResult runInNewProcess() throws IOException, InterruptedException {
    logDebug(debug, "[%s]", deviceIdentifier.toString());

    // Create the output directory.
    work.mkdirs();

    // Write our configuration to a file in the output directory.
    try (FileWriter executionWriter = new FileWriter(new File(work, FILE_EXECUTION))) {
      GSON.toJson(this, executionWriter);
    }

    // Kick off a new process to interface with ADB and perform the real execution.
    String name = SpoonDeviceRunner.class.getName();
    Process process = new ProcessBuilder("java", "-Djava.awt.headless=true", "-cp", classpath, name,
        work.getAbsolutePath()).start();
    printStream(process.getInputStream(), "STDOUT");
    printStream(process.getErrorStream(), "STDERR");

    final int exitCode = process.waitFor();
    logDebug(debug, "Process.waitFor() finished for [%s] with exitCode %d", deviceIdentifier.toString(), exitCode);

    // Read the result from a file in the output directory.
    try (FileReader resultFile = new FileReader(new File(work, FILE_RESULT))) {
      return GSON.fromJson(resultFile, DeviceResult.class);
    }
  }

  private void printStream(InputStream stream, String tag) throws IOException {
    try (BufferedReader stdout = new BufferedReader(new InputStreamReader(stream))) {
      String s;
      while ((s = stdout.readLine()) != null) {
        logDebug(debug, "[%s] %s %s", deviceIdentifier.toString(), tag, s);
      }
    }
  }

  /** Get DeviceIdentifier for the current run **/
  public DeviceIdentifier getDeviceIdentifier() {
    return deviceIdentifier;
  }

  /** Prepare the target device for the instrumentation */
  public void prepareDevice(AndroidDebugBridge adb) {
    device = obtainRealDevice(adb, deviceIdentifier.getSerial());
    logDebug(debug, "Got realDevice for [%s]", deviceIdentifier.toString());

    // Get relevant device information.
    final DeviceDetails deviceDetails = DeviceDetails.createForDevice(device);
    logDebug(debug, "[%s] setDeviceDetails %s", deviceIdentifier.toString(), deviceDetails);

    DdmPreferences.setTimeOut((int) adbTimeout.toMillis());

    // Now install the main application and the instrumentation application.
    try {
      String extraArgument = "";
      if (grantAll && deviceDetails.getApiLevel() >= DeviceDetails.MARSHMALLOW_API_LEVEL) {
        extraArgument = "-g";
      }
      device.installPackage(apk.getAbsolutePath(), true, extraArgument);
    } catch (InstallException e) {
      logInfo("InstallException while install app apk on device [%s]", deviceIdentifier.toString());
      e.printStackTrace(System.out);
      //return result.markInstallAsFailed(
      //    "Unable to install application APK.").addException(e).build();
    }
    try {
      device.installPackage(testApk.getAbsolutePath(), true);
    } catch (InstallException e) {
      logInfo("InstallException while install test apk on device [%s]", deviceIdentifier.toString());
      e.printStackTrace(System.out);
      //return result.markInstallAsFailed(
      //    "Unable to install instrumentation APK.").addException(e).build();
    }

    // If this is Android Marshmallow or above grant WRITE_EXTERNAL_STORAGE
    if (deviceDetails.getApiLevel() >= DeviceDetails.MARSHMALLOW_API_LEVEL) {
      String appPackage = instrumentationInfo.getApplicationPackage();
      try {
        CollectingOutputReceiver grantOutputReceiver = new CollectingOutputReceiver();
        device.executeShellCommand(
            "pm grant " + appPackage + " android.permission.READ_EXTERNAL_STORAGE",
            grantOutputReceiver);
        device.executeShellCommand(
            "pm grant " + appPackage + " android.permission.WRITE_EXTERNAL_STORAGE",
            grantOutputReceiver);
      } catch (Exception e) {
        logInfo("Exception while granting external storage access to application apk"
            + "on device [%s]", deviceIdentifier.toString());
        e.printStackTrace(System.out);
        //return result.markInstallAsFailed(
        //    "Unable to grant external storage access to application APK.").addException(e).build();
      }
    }

    installAndGrantPermission();
  }

  public void installAndGrantPermission() {
    CollectingOutputReceiver installOutPutReceiver = new CollectingOutputReceiver();
    try {

      InputStream is = null;
      OutputStream os = null;

      String tempDir = System.getProperty("java.io.tmpdir");

      try {
        is = SpoonDeviceRunner.class.getResourceAsStream("/" + SPOON_LOCALE_SWITCHER_DEBUG_APK);
        os = new FileOutputStream(new File(tempDir, SPOON_LOCALE_SWITCHER_DEBUG_APK));
        IOUtils.copy(is, os);
      } catch (IOException e) {
        throw new RuntimeException("Unable to copy apk resource to " + tempDir, e);
      } finally {
        IOUtils.closeQuietly(is);
        IOUtils.closeQuietly(os);
      }

      device.installPackage(tempDir + SPOON_LOCALE_SWITCHER_DEBUG_APK, true);
      device.executeShellCommand("pm grant com.squareup.spoonlocale android.permission.CHANGE_CONFIGURATION", installOutPutReceiver);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /** Change the device's current locale*/
  public void changeLocaleAndWait(String locale) throws Exception {
    CollectingOutputReceiver localeOutPutReceiver = new CollectingOutputReceiver();
    deviceIdentifier.setLocale(locale);
    try {
      device.executeShellCommand("pm grant com.squareup.spoonlocale android.permission.CHANGE_CONFIGURATION", localeOutPutReceiver);
      device.executeShellCommand("am broadcast -n com.squareup.spoonlocale/.LocaleSwitcher --es locale " + locale, localeOutPutReceiver);
      Thread.sleep(3000);
    } catch (Exception e) {
      throw new IllegalStateException("Can't change the locale of the device");
    }
  }

  /** Execute instrumentation on the target device and return a result summary. */
  public DeviceResult run() {
    String testPackage = instrumentationInfo.getInstrumentationPackage();
    String testRunner = instrumentationInfo.getTestRunnerClass();

    logDebug(debug, "InstrumentationInfo: [%s]", instrumentationInfo);

    if (debug) {
      SpoonUtils.setDdmlibInternalLoggingLevel();
    }

    DeviceResult.Builder result = new DeviceResult.Builder();

    // Get relevant device information.
    final DeviceDetails deviceDetails = DeviceDetails.createForDevice(device);
    logDebug(debug, "[%s] setDeviceDetails %s", deviceIdentifier.toString(), deviceDetails);
    result.setDeviceDetails(deviceDetails);

    String safeSerial = SpoonUtils.sanitizeSerial(deviceIdentifier.getSerial());

    junitReport = FileUtils.getFile(output, JUNIT_DIR, safeSerial + "-" + deviceDetails.getCurrentLocale() + ".xml");
    imageDir = FileUtils.getFile(output, IMAGE_DIR, safeSerial, deviceDetails.getCurrentLocale());
    fileDir = FileUtils.getFile(output, FILE_DIR, safeSerial, deviceDetails.getCurrentLocale());
    coverageDir = FileUtils.getFile(output, COVERAGE_DIR, safeSerial, deviceDetails.getCurrentLocale()  );

    // Create the output directory, if it does not already exist.
    work.mkdirs();

    // Initiate device logging.
    SpoonDeviceLogger deviceLogger = new SpoonDeviceLogger(device);

    // Run all the tests! o/
    try {
      logDebug(debug, "About to actually run tests for [%s]", deviceIdentifier.toString());
      RemoteAndroidTestRunner runner = new RemoteAndroidTestRunner(testPackage, testRunner, device);
      runner.setMaxTimeToOutputResponse(adbTimeout.toMillis(), TimeUnit.MILLISECONDS);

      if (instrumentationArgs != null && instrumentationArgs.size() > 0) {
        for (String pair : instrumentationArgs) {
          int firstEqualSignIndex = pair.indexOf("=");
          if (firstEqualSignIndex <= -1) {
            // No Equal Sign, can't process
            logDebug(debug, "Can't process instrumentationArg [%s] (no equal sign)", pair);
            continue;
          }
          String key = pair.substring(0, firstEqualSignIndex);
          String value = pair.substring(firstEqualSignIndex + 1);
          if (isNullOrEmpty(key) || isNullOrEmpty(value)) {
            // Invalid values, skipping
            logDebug(debug, "Can't process instrumentationArg [%s] (empty key or value)", pair);
            continue;
          }
          runner.addInstrumentationArg(key, value);
        }
      }
      if (codeCoverage) {
        addCodeCoverageInstrumentationArgs(runner, device);
      }
      // Add the sharding instrumentation arguments if necessary
      if (numShards != 0) {
        addShardingInstrumentationArgs(runner);
      }

      if (!isNullOrEmpty(className)) {
        if (isNullOrEmpty(methodName)) {
          runner.setClassName(className);
        } else {
          runner.setMethodName(className, methodName);
        }
      }
      if (testSize != null) {
        runner.setTestSize(testSize);
      }
      List<ITestRunListener> listeners = new ArrayList<>();
      listeners.add(new SpoonTestRunListener(result, deviceDetails.getCurrentLocale() ,debug));
      listeners.add(new XmlTestRunListener(junitReport));
      if (testRunListeners != null) {
        listeners.addAll(testRunListeners);
      }
      runner.run(listeners);
    } catch (Exception e) {
      result.addException(e);
    }

    mapLogsToTests(deviceLogger, result);

    try {
      logDebug(debug, "About to grab screenshots and prepare output for [%s]", deviceIdentifier.toString());
      pullDeviceFiles(device);
      if (codeCoverage) {
        pullCoverageFile(device);
      }

      cleanScreenshotsDirectory(result, deviceDetails);
      cleanFilesDirectory(result, deviceDetails);

    } catch (Exception e) {
      result.addException(e);
    }
    logDebug(debug, "Done running for [%s]", deviceIdentifier.toString());

    return result.build();
  }

  private void addCodeCoverageInstrumentationArgs(RemoteAndroidTestRunner runner, IDevice device)
          throws Exception {
    String coveragePath = getExternalStoragePath(device, COVERAGE_FILE);
    runner.addInstrumentationArg("coverage", "true");
    runner.addInstrumentationArg("coverageFile", coveragePath);
  }

  private void addShardingInstrumentationArgs(RemoteAndroidTestRunner runner) {
    runner.addInstrumentationArg("numShards", Integer.toString(numShards));
    runner.addInstrumentationArg("shardIndex", Integer.toString(shardIndex));
  }

  private void cleanScreenshotsDirectory(DeviceResult.Builder result, DeviceDetails deviceDetails) throws IOException {
    File screenshotDir = new File(work, DEVICE_SCREENSHOT_DIR);
    if (screenshotDir.exists()) {
      imageDir.mkdirs();
      handleImages(result, screenshotDir, deviceDetails);
      FileUtils.deleteDirectory(screenshotDir);
    }
  }

  private void cleanFilesDirectory(DeviceResult.Builder result, DeviceDetails deviceDetails) throws IOException {
    File testFilesDir = new File(work, DEVICE_FILE_DIR);
    if (testFilesDir.exists()) {
      fileDir.mkdirs();
      handleFiles(result, testFilesDir, deviceDetails);
      FileUtils.deleteDirectory(testFilesDir);
    }
  }

  private void pullCoverageFile(IDevice device) {
    coverageDir.mkdirs();
    File coverageFile = new File(coverageDir, COVERAGE_FILE);
    String remotePath;
    try {
      remotePath = getExternalStoragePath(device, COVERAGE_FILE);
    } catch (Exception exception) {
      throw new RuntimeException("error while calculating coverage file path.", exception);
    }
    adbPullFile(device, remotePath, coverageFile.getAbsolutePath());
  }

  private void handleImages(DeviceResult.Builder result, File screenshotDir, DeviceDetails deviceDetails) throws IOException {
    logDebug(debug, "Moving screenshots to the image folder on [%s]", deviceIdentifier.toString());
    // Move all children of the screenshot directory into the image folder.
    File[] classNameDirs = screenshotDir.listFiles();
    if (classNameDirs != null) {
      Multimap<DeviceTest, File> testScreenshots = ArrayListMultimap.create();
      for (File classNameDir : classNameDirs) {

        String className = classNameDir.getName();
        File destDir = new File(imageDir, className);
        FileUtils.copyDirectory(classNameDir, destDir);

        // Get a sorted list of all screenshots from the device run.
        List<File> screenshots = new ArrayList<>(
            FileUtils.listFiles(destDir, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE));
        Collections.sort(screenshots);

        // Iterate over each screenshot and associate it with its corresponding method result.
        for (File screenshot : screenshots) {
          String methodName = screenshot.getParentFile().getName();

          DeviceTest testIdentifier = new DeviceTest(className, methodName, deviceDetails.getCurrentLocale());
          DeviceTestResult.Builder builder = result.getMethodResultBuilder(testIdentifier);
          if (builder != null) {
            builder.addScreenshot(screenshot);
            testScreenshots.put(testIdentifier, screenshot);
          } else {
            logError("Unable to find test for %s", testIdentifier);
          }
        }
      }

      logDebug(debug, "Generating animated gifs for [%s]", deviceIdentifier.toString());
      // Don't generate animations if the switch is present
      if (!noAnimations) {
        // Make animated GIFs for all the tests which have screenshots.
        for (DeviceTest deviceTest : testScreenshots.keySet()) {
          List<File> screenshots = new ArrayList<>(testScreenshots.get(deviceTest));
          if (screenshots.size() == 1) {
            continue; // Do not make an animated GIF if there is only one screenshot.
          }
          File animatedGif = FileUtils.getFile(imageDir, deviceTest.getClassName(),
              deviceTest.getMethodName() + ".gif");
          createAnimatedGif(screenshots, animatedGif);
          result.getMethodResultBuilder(deviceTest).setAnimatedGif(animatedGif);
        }
      }
    }
  }

  private void handleFiles(DeviceResult.Builder result, File testFileDir, DeviceDetails deviceDetails) throws IOException {
    File[] classNameDirs = testFileDir.listFiles();
    if (classNameDirs != null) {
      logInfo("Found class name dirs: " + Arrays.toString(classNameDirs));
      for (File classNameDir : classNameDirs) {
        String className = classNameDir.getName();
        File destDir = new File(fileDir, className);
        FileUtils.copyDirectory(classNameDir, destDir);
        logInfo("Copied " + classNameDir + " to " + destDir);

        // Get a sorted list of all files from the device run.
        List<File> files = new ArrayList<>(
            FileUtils.listFiles(destDir, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE));
        Collections.sort(files);

        // Iterate over each file and associate it with its
        // corresponding method result.
        for (File file : files) {
          String methodName = file.getParentFile().getName();
          DeviceTest testIdentifier = new DeviceTest(className, methodName, deviceDetails.getCurrentLocale());
          final DeviceTestResult.Builder resultBuilder =
              result.getMethodResultBuilder(testIdentifier);
          if (resultBuilder != null ) {
            resultBuilder.addFile(file);
            logInfo("Added file as result: " + file + " for " + testIdentifier);
          } else {
            logError("Unable to find test for %s", testIdentifier);
          }
        }
      }
    }
  }

  /** Download all files from a single device to the local machine. */
  private void pullDeviceFiles(IDevice device) throws Exception {
    for (String dir : DEVICE_DIRS) {
      pullDirectory(device, dir);
    }
  }

  private void pullDirectory(final IDevice device, final String name) throws Exception {
    // Output path on private internal storage, for KitKat and below.
    FileEntry internalDir = getDirectoryOnInternalStorage(name);
    logDebug(debug, "Internal path is " + internalDir.getFullPath());

    // Output path on public external storage, for Lollipop and above.
    FileEntry externalDir = getDirectoryOnExternalStorage(device, name);
    logDebug(debug, "External path is " + externalDir.getFullPath());

    // Sync test output files to the local filesystem.
    logDebug(debug, "Pulling files from external dir on [%s]", deviceIdentifier.toString());
    String localDirName = work.getAbsolutePath();
    adbPull(device, externalDir, localDirName);
    logDebug(debug, "Pulling files from internal dir on [%s]", deviceIdentifier.toString());
    adbPull(device, internalDir, localDirName);
    logDebug(debug, "Done pulling %s from on [%s]", name, deviceIdentifier.toString());
  }

  private void adbPull(IDevice device, FileEntry remoteDirName, String localDirName) {
    try {
      device.getSyncService().pull(new FileEntry[]{remoteDirName}, localDirName,
          getNullProgressMonitor());
    } catch (Exception e) {
      logDebug(debug, e.getMessage(), e);
    }
  }

  private void adbPullFile(IDevice device, String remoteFile, String localDir) {
    try {
      device.getSyncService()
          .pullFile(remoteFile, localDir, getNullProgressMonitor());
    } catch (Exception e) {
      logDebug(debug, e.getMessage(), e);
    }
  }

  private FileEntry getDirectoryOnInternalStorage(final String dir) {
    String internalPath = getInternalPath(dir);
    return obtainDirectoryFileEntry(internalPath);
  }

  private String getInternalPath(String path) {
    String appPackage = instrumentationInfo.getApplicationPackage();
    return "/data/data/" + appPackage + "/" + path;
  }

  private FileEntry getDirectoryOnExternalStorage(IDevice device, final String dir)
      throws Exception {
    String externalPath = getExternalStoragePath(device, dir);
    return obtainDirectoryFileEntry(externalPath);
  }

  private String getExternalStoragePath(IDevice device, final String path) throws Exception {
    CollectingOutputReceiver pathNameOutputReceiver = new CollectingOutputReceiver();
    device.executeShellCommand("echo $EXTERNAL_STORAGE", pathNameOutputReceiver);
    return pathNameOutputReceiver.getOutput().trim() + "/" + path;
  }

  /** Grab all the parsed logs and map them to individual tests. */
  private static void mapLogsToTests(SpoonDeviceLogger deviceLogger, DeviceResult.Builder result) {
    Map<DeviceTest, List<LogCatMessage>> logs = deviceLogger.getParsedLogs();
    for (Map.Entry<DeviceTest, List<LogCatMessage>> entry : logs.entrySet()) {
      DeviceTestResult.Builder builder = result.getMethodResultBuilder(entry.getKey());
      if (builder != null) {
        builder.setLog(entry.getValue());
      }
    }
  }

  /////////////////////////////////////////////////////////////////////////////
  ////  Secondary Per-Device Process  /////////////////////////////////////////
  /////////////////////////////////////////////////////////////////////////////

  /** De-serialize from disk, run the tests, and serialize the result back to disk. */
  public static void main(String... args) {
    if (args.length != 1) {
      throw new IllegalArgumentException("Must be started with a device directory.");
    }

    try {
      String outputDirName = args[0];
      File outputDir = new File(outputDirName);
      File executionFile = new File(outputDir, FILE_EXECUTION);
      if (!executionFile.exists()) {
        throw new IllegalArgumentException("Device directory and/or execution file doesn't exist.");
      }

      SpoonDeviceRunner target;
      try (FileReader reader = new FileReader(executionFile)) {
        target = GSON.fromJson(reader, SpoonDeviceRunner.class);
      }

      System.out.println("using deivceIdentifier: " + target.deviceIdentifier.toString());

      AndroidDebugBridge adb = SpoonUtils.initAdb(target.sdk, target.adbTimeout);
      target.prepareDevice(adb);
      target.changeLocaleAndWait(target.deviceIdentifier.getLocale());
      DeviceResult result = target.run();
      AndroidDebugBridge.terminate();

      // Write device result file.
      try (FileWriter writer = new FileWriter(new File(outputDir, FILE_RESULT))) {
        GSON.toJson(result, writer);
      }
    } catch (Throwable ex) {
      logInfo("ERROR: Unable to execute test for target.  Exception message: %s", ex.getMessage());
      ex.printStackTrace(System.out);
      System.exit(1);
    }
  }
}
