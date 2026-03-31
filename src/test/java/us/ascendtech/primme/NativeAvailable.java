package us.ascendtech.primme;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Skips the test class when the PRIMME native library is not on the classpath.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(NativeAvailable.Condition.class)
@interface NativeAvailable {

    class Condition implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            String platform = detectPlatform();
            if (platform == null) {
                return ConditionEvaluationResult.disabled("Unsupported platform");
            }
            String libName = System.mapLibraryName("primme");
            String resource = "/native/" + platform + "/" + libName;
            if (NativeAvailable.class.getResource(resource) != null) {
                return ConditionEvaluationResult.enabled("Native library found: " + resource);
            }
            return ConditionEvaluationResult.disabled("Native library not found: " + resource);
        }

        private static String detectPlatform() {
            String os = System.getProperty("os.name", "").toLowerCase();
            String arch = System.getProperty("os.arch", "").toLowerCase();

            String osKey;
            if (os.contains("linux")) osKey = "linux";
            else if (os.contains("mac") || os.contains("darwin")) osKey = "macos";
            else if (os.contains("win")) osKey = "windows";
            else return null;

            String archKey;
            if (arch.equals("amd64") || arch.equals("x86_64")) archKey = "x86_64";
            else if (arch.equals("aarch64") || arch.equals("arm64")) archKey = "aarch64";
            else return null;

            return osKey + "-" + archKey;
        }
    }
}
