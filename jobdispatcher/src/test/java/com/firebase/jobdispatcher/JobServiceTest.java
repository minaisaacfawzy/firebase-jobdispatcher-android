// Copyright 2016 Google, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////

package com.firebase.jobdispatcher;

import static com.firebase.jobdispatcher.GooglePlayReceiver.getJobCoder;
import static com.firebase.jobdispatcher.TestUtil.flushExecutorService;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.v4.util.Pair;
import com.firebase.jobdispatcher.JobInvocation.Builder;
import com.google.android.gms.gcm.PendingCallback;
import com.google.common.util.concurrent.SettableFuture;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSystemClock;

/** Tests for the {@link JobService} class. */
@RunWith(RobolectricTestRunner.class)
@Config(
    manifest = Config.NONE,
    sdk = 23,
    shadows = {ShadowSystemClock.class})
public class JobServiceTest {

  private static final int TIMEOUT_MS = 3_000;

  private static CountDownLatch countDownLatch;

  private final IJobCallback noopCallback =
      new IJobCallback.Stub() {
        @Override
        public void jobFinished(Bundle invocationData, @JobService.JobResult int result) {}
      };

  @Before
  public void setUp() {}

  @After
  public void tearDown() {
    countDownLatch = null;
  }

  @Test
  public void testOnStartCommand_handlesNullIntent() {
    JobService service = spy(new ExampleJobService());
    int startId = 7;

    try {
      service.onStartCommand(null, 0, startId);

      verify(service).stopSelf(startId);
    } catch (NullPointerException npe) {
      fail("Unexpected NullPointerException after calling onStartCommand with a null Intent.");
    }
  }

  @Test
  public void testOnStartCommand_handlesNullAction() {
    JobService service = spy(new ExampleJobService());
    int startId = 7;

    Intent nullActionIntent = new Intent();
    service.onStartCommand(nullActionIntent, 0, startId);

    verify(service).stopSelf(startId);
  }

  @Test
  public void testOnStartCommand_handlesEmptyAction() {
    JobService service = spy(new ExampleJobService());
    int startId = 7;

    Intent emptyActionIntent = new Intent("");
    service.onStartCommand(emptyActionIntent, 0, startId);

    verify(service).stopSelf(startId);
  }

  @Test
  public void testOnStartCommand_handlesUnknownAction() {
    JobService service = spy(new ExampleJobService());
    int startId = 7;

    Intent emptyActionIntent = new Intent("foo.bar.baz");
    service.onStartCommand(emptyActionIntent, 0, startId);

    verify(service).stopSelf(startId);
  }

  @Test
  public void testOnStartCommand_handlesStartJob_nullData() {
    JobService service = spy(new ExampleJobService());
    int startId = 7;

    Intent executeJobIntent = new Intent(JobService.ACTION_EXECUTE);
    service.onStartCommand(executeJobIntent, 0, startId);

    verify(service).stopSelf(startId);
  }

  @Test
  public void testOnStartCommand_handlesStartJob_noTag() {
    JobService service = spy(new ExampleJobService());
    int startId = 7;

    Intent executeJobIntent = new Intent(JobService.ACTION_EXECUTE);
    Parcel p = Parcel.obtain();
    p.writeStrongBinder(mock(IBinder.class));
    executeJobIntent.putExtra("callback", new PendingCallback(p));

    service.onStartCommand(executeJobIntent, 0, startId);

    verify(service).stopSelf(startId);

    p.recycle();
  }

  @Test
  public void testOnStartCommand_handlesStartJob_noCallback() {
    JobService service = spy(new ExampleJobService());
    int startId = 7;

    Intent executeJobIntent = new Intent(JobService.ACTION_EXECUTE);
    executeJobIntent.putExtra("tag", "foobar");

    service.onStartCommand(executeJobIntent, 0, startId);

    verify(service).stopSelf(startId);
  }

  @Test
  public void testOnStartCommand_handlesStartJob_validRequest() throws Exception {
    JobService service = spy(new ExampleJobService());

    Job jobSpec =
        TestUtil.getBuilderWithNoopValidator()
            .setTag("tag")
            .setService(ExampleJobService.class)
            .setRetryStrategy(RetryStrategy.DEFAULT_EXPONENTIAL)
            .setTrigger(Trigger.NOW)
            .setLifetime(Lifetime.FOREVER)
            .build();

    countDownLatch = new CountDownLatch(1);

    Bundle jobSpecData = getJobCoder().encode(jobSpec, new Bundle());
    IRemoteJobService remoteJobService =
        IRemoteJobService.Stub.asInterface(service.onBind(new Intent(JobService.ACTION_EXECUTE)));
    remoteJobService.start(jobSpecData, noopCallback);

    flush(service);

    assertTrue("Expected job to run to completion", countDownLatch.await(5, TimeUnit.SECONDS));
  }

  @Test
  public void testOnStartCommand_handlesStartJob_doNotStartRunningJobAgain() throws Exception {
    StoppableJobService service = new StoppableJobService(/* shouldReschedule= */ false);

    Job jobSpec =
        TestUtil.getBuilderWithNoopValidator()
            .setTag("tag")
            .setService(StoppableJobService.class)
            .setTrigger(Trigger.NOW)
            .build();

    Bundle jobSpecData = getJobCoder().encode(jobSpec, new Bundle());
    IRemoteJobService.Stub.asInterface(service.onBind(null)).start(jobSpecData, null);
    IRemoteJobService.Stub.asInterface(service.onBind(null)).start(jobSpecData, null);

    flush(service);

    assertEquals(1, service.getNumberOfStartRequestsReceived());
  }

  @Test
  public void stop_noCallback_finished() throws Exception {
    JobService service = spy(new StoppableJobService(/* shouldReschedule= */ false));
    JobInvocation job =
        new Builder()
            .setTag("Tag")
            .setTrigger(Trigger.NOW)
            .setService(StoppableJobService.class.getName())
            .build();

    IRemoteJobService.Stub.asInterface(service.onBind(null))
        .stop(getJobCoder().encode(job, new Bundle()), true);

    flush(service);

    verify(service, never()).onStopJob(job);
  }

  @Test
  public void stop_withCallback_retry() throws Exception {
    StoppableJobService service = spy(new StoppableJobService(/* shouldReschedule= */ false));

    JobInvocation job =
        new Builder()
            .setTag("Tag")
            .setTrigger(Trigger.NOW)
            .setService(StoppableJobService.class.getName())
            .build();

    Bundle jobSpecData = getJobCoder().encode(job, new Bundle());
    FutureSettingJobCallback callback = new FutureSettingJobCallback();

    // start the service
    IRemoteJobService.Stub.asInterface(service.onBind(null)).start(jobSpecData, callback);
    IRemoteJobService.Stub.asInterface(service.onBind(null)).stop(jobSpecData, true);

    flush(service);

    assertEquals(1, service.getNumberOfStopRequestsReceived());
    callback.verifyCalledWithJobAndResult(job, JobService.RESULT_SUCCESS);
  }

  @Test
  public void stop_withCallback_done() throws Exception {
    StoppableJobService service = spy(new StoppableJobService(/* shouldReschedule= */ true));

    JobInvocation job =
        new Builder()
            .setTag("Tag")
            .setTrigger(Trigger.NOW)
            .setService(StoppableJobService.class.getName())
            .build();

    Bundle jobSpecData = getJobCoder().encode(job, new Bundle());
    FutureSettingJobCallback callback = new FutureSettingJobCallback();

    IRemoteJobService.Stub.asInterface(service.onBind(null)).start(jobSpecData, callback);
    IRemoteJobService.Stub.asInterface(service.onBind(null)).stop(jobSpecData, true);

    flush(service);

    assertEquals(1, service.getNumberOfStopRequestsReceived());
    callback.verifyCalledWithJobAndResult(job, JobService.RESULT_FAIL_RETRY);
  }

  @Test
  public void onStartJob_jobFinishedReschedule() throws Exception {
    // Verify that a retry request from within onStartJob will cause the retry result to be sent
    // to the bouncer service's handler, regardless of what value is ultimately returned from
    // onStartJob.
    JobService reschedulingService =
        new JobService() {
          @Override
          public boolean onStartJob(@NonNull JobParameters job) {
            // Reschedules job.
            jobFinished(job, true /* retry this job */);
            return false;
          }

          @Override
          public boolean onStopJob(@NonNull JobParameters job) {
            return false;
          }
        };

    Job jobSpec =
        TestUtil.getBuilderWithNoopValidator()
            .setTag("tag")
            .setService(reschedulingService.getClass())
            .setTrigger(Trigger.NOW)
            .build();

    FutureSettingJobCallback callback = new FutureSettingJobCallback();
    IRemoteJobService.Stub.asInterface(reschedulingService.onBind(null))
        .start(getJobCoder().encode(jobSpec, new Bundle()), callback);

    flush(reschedulingService);

    callback.verifyCalledWithJobAndResult(jobSpec, JobService.RESULT_FAIL_RETRY);
  }

  @Test
  public void onStartJob_jobFinishedNotReschedule() throws Exception {
    // Verify that a termination request from within onStartJob will cause the result to be sent
    // to the bouncer service's handler, regardless of what value is ultimately returned from
    // onStartJob.
    JobService reschedulingService =
        new JobService() {
          @Override
          public boolean onStartJob(@NonNull JobParameters job) {
            jobFinished(job, false /* don't retry this job */);
            return false;
          }

          @Override
          public boolean onStopJob(@NonNull JobParameters job) {
            return false;
          }
        };

    Job jobSpec =
        TestUtil.getBuilderWithNoopValidator()
            .setTag("tag")
            .setService(reschedulingService.getClass())
            .setTrigger(Trigger.NOW)
            .build();

    FutureSettingJobCallback callback = new FutureSettingJobCallback();
    IRemoteJobService.Stub.asInterface(reschedulingService.onBind(null))
        .start(getJobCoder().encode(jobSpec, new Bundle()), callback);

    flush(reschedulingService);

    callback.verifyCalledWithJobAndResult(jobSpec, JobService.RESULT_SUCCESS);
  }

  @Test
  public void onUnbind_removesUsedCallbacks_withBackgroundWork() throws Exception {
    verifyOnUnbindCausesResult(
        new JobService() {
          @Override
          public boolean onStartJob(@NonNull JobParameters job) {
            return true; // More work to do in background
          }

          @Override
          public boolean onStopJob(@NonNull JobParameters job) {
            return true; // Still doing background work
          }
        },
        JobService.RESULT_FAIL_RETRY);
  }

  @Test
  public void onUnbind_removesUsedCallbacks_noBackgroundWork() throws Exception {
    verifyOnUnbindCausesResult(
        new JobService() {
          @Override
          public boolean onStartJob(@NonNull JobParameters job) {
            return true; // more work to do in background
          }

          @Override
          public boolean onStopJob(@NonNull JobParameters job) {
            return false; // Done with background work
          }
        },
        JobService.RESULT_FAIL_NORETRY);
  }

  @Test
  public void onStop_calledOnMainThread() throws Exception {
    final SettableFuture<Looper> looperFuture = SettableFuture.create();
    final JobService service =
        new JobService() {
          @Override
          public boolean onStartJob(@NonNull JobParameters job) {
            return true; // more work to do
          }

          @Override
          public boolean onStopJob(@NonNull JobParameters job) {
            looperFuture.set(Looper.myLooper());
            return false;
          }
        };

    Job jobSpec =
        TestUtil.getBuilderWithNoopValidator()
            .setTag("tag")
            .setService(service.getClass())
            .setTrigger(Trigger.NOW)
            .build();

    final Bundle jobSpecData = getJobCoder().encode(jobSpec, new Bundle());
    IRemoteJobService.Stub.asInterface(service.onBind(null)).start(jobSpecData, noopCallback);

    // call stopJob on a background thread and wait for it
    Executors.newSingleThreadExecutor()
        .submit(
            new Runnable() {
              @Override
              public void run() {
                try {
                  IRemoteJobService.Stub.asInterface(service.onBind(null)).stop(jobSpecData, true);
                } catch (RemoteException e) {
                  throw new AssertionError("calling stop on binder unexpectedly failed", e);
                }
              }
            })
        .get(1, TimeUnit.SECONDS);

    flush(service);

    assertEquals(
        "onStopJob was not called on main thread",
        Looper.getMainLooper(),
        looperFuture.get(1, TimeUnit.SECONDS));
  }

  @Test
  public void dump_noTasksStarted() throws Exception {
    assertThat(dump(new ExampleJobService())).isEqualTo("No running jobs\n");
  }

  @Test
  public void dump_oneRunningJob() throws Exception {
    countDownLatch = new CountDownLatch(1);
    JobService service =
        new JobService() {
          @Override
          public boolean onStartJob(JobParameters job) {
            countDownLatch.countDown();
            return true; // more work to do
          }

          @Override
          public boolean onStopJob(JobParameters job) {
            return false;
          }
        };

    Job jobSpec =
        TestUtil.getBuilderWithNoopValidator()
            .setTag("one_running_job")
            .setService(service.getClass())
            .setTrigger(Trigger.NOW)
            .build();

    Bundle jobSpecData = getJobCoder().encode(jobSpec, new Bundle());
    FutureSettingJobCallback callback = new FutureSettingJobCallback();
    IRemoteJobService stub = IRemoteJobService.Stub.asInterface(service.onBind(null));

    ShadowSystemClock.setCurrentTimeMillis(10_000L);
    // Start the job
    stub.start(jobSpecData, callback);
    flush(service);

    // Make sure it was started
    assertThat(countDownLatch.await(1, TimeUnit.SECONDS)).isTrue();
    // Fast forward 30s
    ShadowSystemClock.setCurrentTimeMillis(40_000L);

    assertThat(dump(service))
        .isEqualTo("Running jobs:\n    * \"one_running_job\" has been running for 00:30\n");

    stub.stop(jobSpecData, /* needToSendResult= */ false);
    flush(service);

    assertThat(dump(service)).isEqualTo("No running jobs\n");
  }

  private static String dump(JobService service) throws Exception {
    StringWriter sw = new StringWriter();
    service.dumpImpl(new PrintWriter(sw));
    return sw.toString();
  }

  private static void flush(JobService jobService) throws Exception {
    flushExecutorService(jobService.backgroundExecutor);
    ShadowLooper.idleMainLooper();
  }

  private static void verifyOnUnbindCausesResult(JobService service, int expectedResult)
      throws Exception {
    Job jobSpec =
        TestUtil.getBuilderWithNoopValidator()
            .setTag("tag")
            .setService(service.getClass())
            .setTrigger(Trigger.NOW)
            .build();

    Bundle jobSpecData = getJobCoder().encode(jobSpec, new Bundle());
    FutureSettingJobCallback callback = new FutureSettingJobCallback();

    // start the service
    IRemoteJobService.Stub.asInterface(service.onBind(null)).start(jobSpecData, callback);
    // shouldn't have sent a result message yet (still doing background work)
    assertFalse(callback.getJobFinishedFuture().isDone());
    // manually trigger the onUnbind hook
    service.onUnbind(new Intent());

    flush(service);

    callback.verifyCalledWithJobAndResult(jobSpec, expectedResult);

    // Calling jobFinished should not attempt to send a second message
    callback.reset();
    service.jobFinished(jobSpec, false);
    assertFalse(callback.getJobFinishedFuture().isDone());
  }

  private static class FutureSettingJobCallback extends IJobCallback.Stub {
    SettableFuture<Pair<Bundle, Integer>> jobFinishedFuture = SettableFuture.create();

    SettableFuture<Pair<Bundle, Integer>> getJobFinishedFuture() {
      return jobFinishedFuture;
    }

    void reset() {
      jobFinishedFuture = SettableFuture.create();
    }

    void verifyCalledWithJobAndResult(JobParameters job, int result) throws Exception {
      Pair<Bundle, Integer> jobFinishedResult =
          getJobFinishedFuture().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
      assertNotNull(jobFinishedResult);

      JobCoder jc = getJobCoder();
      assertEquals(
          // re-encode so they're the same class
          jc.decode(jc.encode(job, new Bundle())).build(),
          jc.decode(jobFinishedResult.first).build());
      assertEquals(result, (int) jobFinishedResult.second);
    }

    @Override
    public void jobFinished(Bundle invocationData, @JobService.JobResult int result) {
      jobFinishedFuture.set(Pair.create(invocationData, result));
    }
  }

  /** A simple JobService that just counts down the {@link #countDownLatch}. */
  public static class ExampleJobService extends JobService {
    @Override
    public boolean onStartJob(@NonNull JobParameters job) {
      countDownLatch.countDown();
      return false;
    }

    @Override
    public boolean onStopJob(@NonNull JobParameters job) {
      return false;
    }
  }

  /** A JobService that allows customizing the onStopJob result. */
  public static class StoppableJobService extends JobService {

    private final boolean shouldReschedule;
    private final AtomicInteger numberOfStartRequestsReceived = new AtomicInteger();
    private final AtomicInteger numberOfStopRequestsReceived = new AtomicInteger();

    public StoppableJobService(boolean shouldReschedule) {
      this.shouldReschedule = shouldReschedule;
    }

    @Override
    public boolean onStartJob(@NonNull JobParameters job) {
      numberOfStartRequestsReceived.incrementAndGet();
      return true;
    }

    @Override
    public boolean onStopJob(@NonNull JobParameters job) {
      numberOfStopRequestsReceived.incrementAndGet();
      return shouldReschedule;
    }

    public int getNumberOfStartRequestsReceived() {
      return numberOfStartRequestsReceived.get();
    }

    public int getNumberOfStopRequestsReceived() {
      return numberOfStopRequestsReceived.get();
    }
  }
}
