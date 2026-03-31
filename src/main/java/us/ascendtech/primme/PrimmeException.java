package us.ascendtech.primme;

/**
 * Thrown when a PRIMME solver call returns a non-zero error code.
 */
public class PrimmeException extends RuntimeException {

    private final int errorCode;

    public PrimmeException(int errorCode) {
        super(messageFor(errorCode));
        this.errorCode = errorCode;
    }

    public int errorCode() {
        return errorCode;
    }

    private static String messageFor(int code) {
        return switch (code) {
            case -1  -> "Unexpected failure";
            case -2  -> "Memory allocation failure";
            case -3  -> "Main iteration failure";
            case -40 -> "LAPACK failure";
            case -41 -> "User-provided function failure";
            case -42 -> "Orthogonalization of constraints failure";
            case -43 -> "Parallel communication failure";
            case -44 -> "Function unavailable";
            default  -> "PRIMME error code " + code;
        };
    }
}
