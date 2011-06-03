package ws.palladian.helper;

import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang.mutable.MutableInt;

/**
 * Simple and thread safe up/down counter
 * 
 * @deprecated For simple applications, use int; if you need to pass references, use {@link MutableInt}; if you need
 *             Thread-safety, use {@link AtomicInteger} instead.
 * @author Philipp Katz
 */
@Deprecated
public class Counter {

    // private int count = 0;
    private AtomicInteger count = new AtomicInteger();

    public/* synchronized */int increment() {
        // return ++count;
        return count.incrementAndGet();
    }

    public/* synchronized */int decrement() {
        // return --count;
        return count.decrementAndGet();
    }

    public/* synchronized */int increment(int by) {
        // count += by;
        // return count;
        return count.addAndGet(by);
    }

    public/* synchronized */int getCount() {
        // return count;
        return count.get();
    }

    public/* synchronized */void reset() {
        // count = 0;
        count.set(0);
    }

    @Override
    public String toString() {
        return String.valueOf(getCount());
    }

}