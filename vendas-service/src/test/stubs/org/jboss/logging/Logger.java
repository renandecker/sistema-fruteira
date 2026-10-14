package org.jboss.logging;
public class Logger {
    public static Logger getLogger(Class<?> c) { return new Logger(); }
    public void warn(Object m) { System.out.println("  [log] " + m); }
    public void info(Object m) {}
    public void infof(String f, Object... a) {}
    public void warnf(String f, Object... a) {}
}
