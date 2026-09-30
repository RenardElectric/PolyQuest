package polycube.polyquest.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/// Compact, definition-ordered state for a condition tree. Consecutive booleans share one byte.
public final class ProgressState {
    public static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final long serverTick;
        private final long epochMillis;
        private int bits;
        private int bitCount;

        public Writer(long serverTick, long epochMillis) {
            this.serverTick = serverTick;
            this.epochMillis = epochMillis;
        }

        public long serverTick() { return serverTick; }
        public long epochMillis() { return epochMillis; }

        public void booleanValue(boolean value) {
            if (value) bits |= 1 << bitCount;
            if (++bitCount == 8) flushBits();
        }

        public void unsignedInt(int value) {
            if (value < 0) throw new IllegalArgumentException("Negative progress value");
            flushBits();
            while ((value & ~0x7f) != 0) {
                output.write((value & 0x7f) | 0x80);
                value >>>= 7;
            }
            output.write(value);
        }

        public void unsignedLong(long value) {
            if (value < 0) throw new IllegalArgumentException("Negative progress value");
            flushBits();
            while ((value & ~0x7fL) != 0) {
                output.write((int) (value & 0x7fL) | 0x80);
                value >>>= 7;
            }
            output.write((int) value);
        }

        public void string(String value) {
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            unsignedInt(encoded.length);
            output.writeBytes(encoded);
        }

        public byte[] bytes() {
            flushBits();
            return output.toByteArray();
        }

        private void flushBits() {
            if (bitCount == 0) return;
            output.write(bits);
            bits = 0;
            bitCount = 0;
        }
    }

    public static final class Reader {
        private final ByteArrayInputStream input;
        private final long serverTick;
        private final long epochMillis;
        private int bits;
        private int bitOffset = 8;

        public Reader(byte[] bytes, long serverTick, long epochMillis) {
            input = new ByteArrayInputStream(bytes);
            this.serverTick = serverTick;
            this.epochMillis = epochMillis;
        }

        public long serverTick() { return serverTick; }
        public long epochMillis() { return epochMillis; }

        public boolean booleanValue() {
            if (bitOffset == 8) {
                bits = nextByte();
                bitOffset = 0;
            }
            return ((bits >>> bitOffset++) & 1) != 0;
        }

        public int unsignedInt(int maximum) {
            align();
            int result = 0;
            for (int shift = 0; shift <= 28; shift += 7) {
                int next = nextByte();
                if (shift == 28 && (next & 0xf0) != 0) throw new IllegalArgumentException("Progress integer overflow");
                result |= (next & 0x7f) << shift;
                if ((next & 0x80) == 0) {
                    if (result < 0 || result > maximum) throw new IllegalArgumentException("Progress integer out of range");
                    return result;
                }
            }
            throw new IllegalArgumentException("Progress integer is too long");
        }

        public long unsignedLong() {
            align();
            long result = 0L;
            for (int shift = 0; shift <= 63; shift += 7) {
                int next = nextByte();
                if (shift == 63 && (next & 0xfe) != 0) throw new IllegalArgumentException("Progress long overflow");
                result |= (long) (next & 0x7f) << shift;
                if ((next & 0x80) == 0) {
                    if (result < 0) throw new IllegalArgumentException("Progress long out of range");
                    return result;
                }
            }
            throw new IllegalArgumentException("Progress long is too long");
        }

        public String string() {
            int length = unsignedInt(32767);
            byte[] bytes = new byte[length];
            if (input.read(bytes, 0, length) != length) throw new IllegalArgumentException("Truncated progress string");
            return new String(bytes, StandardCharsets.UTF_8);
        }

        public void finish() {
            align();
            if (input.available() != 0) throw new IllegalArgumentException("Trailing progress bytes");
        }

        private void align() {
            if (bitOffset < 8 && (bits >>> bitOffset) != 0) {
                throw new IllegalArgumentException("Nonzero progress padding");
            }
            bitOffset = 8;
        }

        private int nextByte() {
            int value = input.read();
            if (value < 0) throw new IllegalArgumentException("Truncated progress bytes");
            return value;
        }
    }

    private ProgressState() {}
}
