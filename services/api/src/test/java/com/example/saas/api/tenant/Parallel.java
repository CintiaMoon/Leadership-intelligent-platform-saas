package com.example.saas.api.tenant;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Runs tasks at the same moment (all wait at a barrier, then start) and returns their results in task order. */
final class Parallel {

	private Parallel() {
	}

	static <T> List<T> runAll(List<Callable<T>> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		try {
			CyclicBarrier start = new CyclicBarrier(tasks.size());
			List<Future<T>> futures = new ArrayList<>();
			for (Callable<T> task : tasks) {
				futures.add(pool.submit(() -> {
					start.await();
					return task.call();
				}));
			}
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get());
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

	/** The same task, {@code times} times at once. */
	static <T> List<T> runAll(int times, Callable<T> task) throws Exception {
		List<Callable<T>> tasks = new ArrayList<>();
		for (int i = 0; i < times; i++) {
			tasks.add(task);
		}
		return runAll(tasks);
	}

}
