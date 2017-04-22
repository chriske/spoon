package com.squareup.spoon.html;

import com.android.ddmlib.testrunner.IRemoteAndroidTestRunner;
import com.squareup.spoon.DeviceDetails;
import com.squareup.spoon.DeviceIdentifier;
import com.squareup.spoon.DeviceResult;
import com.squareup.spoon.DeviceTest;
import com.squareup.spoon.DeviceTestResult;
import com.squareup.spoon.SpoonSummary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.squareup.spoon.DeviceTestResult.Status;
import static java.util.stream.Collectors.toList;

/** Model for representing the {@code index.html} page. */
final class HtmlIndex {
  static HtmlIndex from(SpoonSummary summary) {
    int testsRun = 0;
    int totalSuccess = 0;
    List<Device> devices = new ArrayList<>();
    for (Map.Entry<DeviceIdentifier, DeviceResult> result : summary.getResults().entrySet()) {
      devices.add(Device.from(result.getKey(), result.getValue()));
      Map<DeviceTest, DeviceTestResult> testResults = result.getValue().getTestResults();
      testsRun += testResults.size();
      for (Map.Entry<DeviceTest, DeviceTestResult> entry : testResults.entrySet()) {
        if (entry.getValue().getStatus() == Status.PASS) {
          totalSuccess += 1;
        }
      }
    }

    Collections.sort(devices);

    int totalFailure = testsRun - totalSuccess;

    int deviceCount = summary.getResults().size();
    IRemoteAndroidTestRunner.TestSize testSize = summary.getTestSize();
    String started = HtmlUtils.dateToString(summary.getStarted());
    String totalTestsRun = testsRun + (testSize != null ? " " + testSize.name().toLowerCase() : "")
        + " test" + (testsRun != 1 ? "s" : "");
    String totalDevices = deviceCount + " device" + (deviceCount != 1 ? "s" : "");

    StringBuilder subtitle = new StringBuilder();
    subtitle.append(totalTestsRun).append(" run across ").append(totalDevices);
    if (testsRun > 0) {
      subtitle.append(" with ")
          .append(totalSuccess)
          .append(" passing and ")
          .append(totalFailure)
          .append(" failing in ")
          .append(HtmlUtils.humanReadableDuration(summary.getDuration()));
    }
    subtitle.append(" at ").append(started);

    return new HtmlIndex(summary.getTitle(), subtitle.toString(),  devices);
  }

  public final String title;
  public final String subtitle;
  public final List<Device> devices;

  HtmlIndex(String title, String subtitle, List<Device> devices) {
    this.title = title;
    this.subtitle = subtitle;
    this.devices = devices;
  }

  static final class Device implements Comparable<Device> {
    static Device from(DeviceIdentifier deviceIdentifier, DeviceResult result) {
      List<TestResult> testResults = result.getTestResults()
          .entrySet()
          .stream()
          .map(entry -> TestResult.from(deviceIdentifier, entry.getKey(), entry.getValue()))
          .collect(toList());
      DeviceDetails details = result.getDeviceDetails();
      String name = (details != null) ? details.getName() : deviceIdentifier.getSerial();
      boolean executionFailed = testResults.isEmpty() && !result.getExceptions().isEmpty();
      return new Device(deviceIdentifier, name, testResults, executionFailed);
    }

    public final DeviceIdentifier deviceIdentifier;
    public final String name;
    public final List<TestResult> testResults;
    public final boolean executionFailed;
    public final int testCount;

    Device(DeviceIdentifier deviceIdentifier, String name, List<TestResult> testResults, boolean executionFailed) {
      this.deviceIdentifier = deviceIdentifier;
      this.name = name;
      this.testResults = testResults;
      this.testCount = testResults.size();
      this.executionFailed = executionFailed;
    }

    @Override public int compareTo(Device other) {
      if (name == null && other.name == null) {
        return deviceIdentifier.compareTo(other.deviceIdentifier);
      }
      if (name == null) {
        return 1;
      }
      if (other.name == null) {
        return -1;
      }
      return name.compareTo(other.name);
    }

    @Override public String toString() {
      return name != null ? name : deviceIdentifier.getSerial();
    }
  }

  static final class TestResult implements Comparable<TestResult> {
    static TestResult from(DeviceIdentifier deviceIdentifier, DeviceTest test, DeviceTestResult testResult) {
      String className = test.getClassName();
      String methodName = test.getMethodName();
      String classSimpleName = HtmlUtils.getClassSimpleName(className);
      String testId = HtmlUtils.testClassAndMethodToId(className, methodName);
      String status = HtmlUtils.getStatusCssClass(testResult);
      return new TestResult(deviceIdentifier, classSimpleName, methodName, testId, status);
    }

    public final DeviceIdentifier deviceIdentifier;
    public final String classSimpleName;
    public final String prettyMethodName;
    public final String testId;
    public final String status;

    TestResult(DeviceIdentifier deviceIdentifier, String classSimpleName, String prettyMethodName, String testId,
        String status) {
      this.deviceIdentifier = deviceIdentifier;
      this.classSimpleName = classSimpleName;
      this.prettyMethodName = prettyMethodName;
      this.testId = testId;
      this.status = status;
    }

    @Override public int compareTo(TestResult other) {
      return 0;
    }
  }
}
