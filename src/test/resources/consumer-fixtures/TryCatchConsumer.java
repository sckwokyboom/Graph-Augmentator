package consumerfix;

import java.io.IOException;

class TryCatchConsumer {
    void target() throws IOException {}

    void wrappedConsumer() {
        try {
            target();
        } catch (IOException e) {
            // swallow
        }
    }

    void unwrappedConsumer() {
        try {
            unrelated();
        } catch (RuntimeException e) {
            // does NOT wrap target()
        }
        target();
    }

    void multiCatchConsumer() {
        try {
            target();
        } catch (IOException | IllegalStateException e) {
            throw new RuntimeException(e);
        }
    }

    void unrelated() {}
}
