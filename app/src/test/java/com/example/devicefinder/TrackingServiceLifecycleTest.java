package com.example.devicefinder;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.net.DatagramSocket;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TrackingServiceLifecycleTest {
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static boolean booleanField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    @Test
    public void destroyClosesIngressAndInterruptsOwnedWorkers() throws Exception {
        ServiceController<TrackingService> controller =
                Robolectric.buildService(TrackingService.class).create();
        TrackingService service = controller.get();
        Thread startup = (Thread) field(service, "startupThread");
        Thread listener = (Thread) field(service, "rescueListenerThread");
        DatagramSocket ingress = (DatagramSocket) field(service, "rescueSocketRef");

        controller.destroy();

        // Startup cancellation is asynchronous; give the worker a bounded opportunity
        // to observe serviceDestroyed and unwind before asserting its terminal state.
        if (startup != null && startup != Thread.currentThread()) {
            startup.join(1000L);
        }

        assertTrue("service must publish teardown before returning",
                booleanField(service, "serviceDestroyed"));
        assertNull("UDP ingress reference must be revoked", field(service, "rescueSocketRef"));
        if (ingress != null) {
            assertTrue("published UDP ingress must be closed", ingress.isClosed());
        }
        if (startup != null) {
            assertTrue("startup worker must be interrupted or already terminated",
                    startup.isInterrupted() || !startup.isAlive());
        }
        if (listener != null) {
            assertTrue("listener worker must be interrupted or already terminated",
                    listener.isInterrupted() || !listener.isAlive());
        }
    }

    @Test
    public void recreatedServiceGetsFreshInstanceIdentityAndLiveLifecycleState() throws Exception {
        ServiceController<TrackingService> firstController =
                Robolectric.buildService(TrackingService.class).create();
        TrackingService first = firstController.get();
        String firstId = (String) field(first, "serviceInstanceId");
        firstController.destroy();

        ServiceController<TrackingService> secondController =
                Robolectric.buildService(TrackingService.class).create();
        TrackingService second = secondController.get();
        try {
            String secondId = (String) field(second, "serviceInstanceId");
            assertNotNull(firstId);
            assertNotNull(secondId);
            assertNotEquals("a recreated service must own a new instance identity",
                    firstId, secondId);
            assertFalse("new service must not inherit destroyed state",
                    booleanField(second, "serviceDestroyed"));
        } finally {
            secondController.destroy();
        }
    }
}
