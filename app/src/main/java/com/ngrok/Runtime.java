/*
 * Android replacement for ngrok-java's com.ngrok.Runtime (ngrok-java 1.1.1, MIT/Apache-2.0).
 *
 * The upstream class copies libngrok_java.so out of the jar into java.io.tmpdir before
 * loading it, which cannot pick the right ABI when several Android natives are bundled.
 * This version loads the library that Gradle packaged into the APK's jniLibs instead.
 * The JNI-facing API (load, init, getLogger, Logger.getLevel/log) is unchanged.
 */
package com.ngrok;

import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

class Runtime {
    private static final Logger LOGGER = new Logger();

    public static Logger getLogger() {
        return LOGGER;
    }

    public static void load() {
        System.loadLibrary("ngrok_java");
    }

    static native void init(Logger logger);

    static class Logger {
        private static final org.slf4j.Logger logger = LoggerFactory.getLogger(Runtime.class);
        private static final String format = "[{}] {}";

        private Logger() {}

        public String getLevel() {
            Level logLevel = Level.INFO;
            Level[] levels = new Level[] {Level.ERROR, Level.WARN, Level.INFO, Level.DEBUG, Level.TRACE};
            for (Level level : levels) {
                if (logger.isEnabledForLevel(level)) {
                    logLevel = level;
                }
            }
            return logLevel.toString();
        }

        public void log(String level, String target, String message) {
            Level lvl = Level.valueOf(level.toUpperCase());
            logger.atLevel(lvl).log(format, target, message);
            if (lvl == Level.ERROR || lvl == Level.WARN) {
                android.util.Log.w("ngrok", target + ": " + message);
            }
        }
    }
}
