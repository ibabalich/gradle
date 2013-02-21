/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.execution.taskgraph;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.execution.TaskExecutionListener;
import org.gradle.api.internal.changedetection.TaskArtifactStateCacheAccess;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.util.Clock;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

class ParallelTaskPlanExecutor extends DefaultTaskPlanExecutor {
    private static final Logger LOGGER = Logging.getLogger(ParallelTaskPlanExecutor.class);

    private final List<Thread> executorThreads = new ArrayList<Thread>();
    private final TaskArtifactStateCacheAccess stateCacheAccess;
    private final int executorCount;

    public ParallelTaskPlanExecutor(TaskArtifactStateCacheAccess cacheAccess, int numberOfParallelExecutors) {
        if (numberOfParallelExecutors < 1) {
            throw new IllegalArgumentException("Not a valid number of parallel executors: " + numberOfParallelExecutors);
        }

        LOGGER.info("Using {} parallel executor threads", numberOfParallelExecutors);

        this.stateCacheAccess = cacheAccess;
        this.executorCount = numberOfParallelExecutors;
    }

    public void process(final TaskExecutionPlan taskExecutionPlan, final TaskExecutionListener taskListener) {
        stateCacheAccess.longRunningOperation("Executing all tasks", new Runnable() {
            public void run() {
                doProcess(taskExecutionPlan, taskListener);
                // TODO This needs to wait until all tasks have been executed, not just started....
                taskExecutionPlan.awaitCompletion();
            }
        });
    }

    private void doProcess(TaskExecutionPlan taskExecutionPlan, TaskExecutionListener taskListener) {
        List<Project> projects = getAllProjects(taskExecutionPlan);
        int numExecutors = Math.min(executorCount, projects.size());

        for (int i = 0; i < numExecutors; i++) {
            TaskExecutorWorker worker = new TaskExecutorWorker(taskExecutionPlan, taskListener);
            executorThreads.add(new Thread(worker));
        }

        for (Thread executorThread : executorThreads) {
            // TODO A bunch more stuff to contextualise the thread
            executorThread.start();
        }
    }

    private List<Project> getAllProjects(TaskExecutionPlan taskExecutionPlan) {
        final Set<Project> uniqueProjects = new LinkedHashSet<Project>();
        for (Task task : taskExecutionPlan.getTasks()) {
            uniqueProjects.add(task.getProject());
        }
        return new ArrayList<Project>(uniqueProjects);
    }

    private class TaskExecutorWorker implements Runnable {
        private final TaskExecutionPlan taskExecutionPlan;
        private final TaskExecutionListener taskListener;
        private long busyMillis;

        private TaskExecutorWorker(TaskExecutionPlan taskExecutionPlan, TaskExecutionListener taskListener) {
            this.taskExecutionPlan = taskExecutionPlan;
            this.taskListener = taskListener;
        }

        public void run() {
            long start = System.currentTimeMillis();
            while(true) {
                TaskInfo task = taskExecutionPlan.getTaskToExecute();
                if (task == null) {
                    break;
                }
                executeTaskWithCacheLock(task);
            }
            long total = System.currentTimeMillis() - start;
            LOGGER.lifecycle("Parallel worker thread [{}] stopped. Was busy for {}, idle for {}", Thread.currentThread(), Clock.prettyTime(busyMillis), Clock.prettyTime(total - busyMillis));
        }

        private void executeTaskWithCacheLock(final TaskInfo taskInfo) {
            final String taskPath = taskInfo.getTask().getPath();
            LOGGER.info(taskPath + " (" + Thread.currentThread() + " - start");
            long start = System.currentTimeMillis();
            stateCacheAccess.useCache("Executing " + taskPath, new Runnable() {
                public void run() {
                    processTask(taskInfo, taskExecutionPlan, taskListener);
                }
            });
            busyMillis += System.currentTimeMillis() - start;

            LOGGER.info(taskPath + " (" + Thread.currentThread() + ") - complete");
        }
    }
}
