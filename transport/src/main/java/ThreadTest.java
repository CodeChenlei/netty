/**
 * chao.yu@dianping.com
 * Created by yuchao on 2016/08/07 20:47.
 */
public class ThreadTest {
    public static void main(String[] args) {
        Thread threads[] = new Thread[100];

        for (int i = 0; i < threads.length; i++) {
            final String threadName = "thread-" + i;
            threads[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        Thread thread = Thread.currentThread();
                        if (!thread.getName().equals(threadName)) {
                            System.out.println(thread.getName() + "|" + threadName);
                        }
                    }
                }
            });
            threads[i].setName(threadName);
        }

        for (Thread thread : threads) {
            thread.start();
        }

    }
}
