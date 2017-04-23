package com.squareup.spoon;

public class DeviceIdentifier implements Comparable<DeviceIdentifier>{
  private String serial;
  private String locale;

  public DeviceIdentifier(String serial, String locale) {
    this.serial = serial;
    this.locale = locale;
  }

  public String getSerial() {
    return serial;
  }

  public void setSerial(String serial) {
    this.serial = serial;
  }

  public String getLocale() {
    return locale;
  }

  public void setLocale(String locale) {
    this.locale = locale;
  }

  @Override public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    DeviceIdentifier that = (DeviceIdentifier) o;
    if (!serial.equals(that.serial)) return false;
    if (!locale.equals(that.locale)) return false;
    return true;
  }

  @Override
  public int hashCode() {
    int result = serial.hashCode();
    result = 31 * result + locale.hashCode();
    return result;
  }

  @Override
  public int compareTo(DeviceIdentifier other) {
    int serialCompare = serial.compareTo(other.serial);
    if (serialCompare != 0) {
      return serialCompare;
    }

    return locale.compareTo(other.locale);
  }

  @Override public String toString() {
    return serial + "-" + locale;
  }

  public String toSafeString() {
    return SpoonUtils.sanitizeSerial(serial) + "-" + locale;
  }
}
