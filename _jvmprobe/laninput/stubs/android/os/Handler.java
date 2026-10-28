package android.os;

public class Handler {
    public Handler(Looper looper) {
    }

    public boolean post(Runnable action) {
        action.run();
        return true;
    }
}
