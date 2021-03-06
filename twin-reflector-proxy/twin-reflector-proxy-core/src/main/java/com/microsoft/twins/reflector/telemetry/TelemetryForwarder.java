/**
 * Copyright (c) Microsoft Corporation. Licensed under the MIT License.
 */
// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license
// information.

package com.microsoft.twins.reflector.telemetry;

import java.io.Closeable;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.microsoft.azure.sdk.iot.device.DeviceClient;
import com.microsoft.azure.sdk.iot.device.IotHubClientProtocol;
import com.microsoft.azure.sdk.iot.device.IotHubEventCallback;
import com.microsoft.azure.sdk.iot.device.IotHubStatusCode;
import com.microsoft.azure.sdk.iot.device.Message;
import com.microsoft.twins.reflector.error.TopologyElementDoesNotExistException;
import com.microsoft.twins.reflector.proxy.DigitalTwinTopologyProxy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
public class TelemetryForwarder implements Closeable {
  private static final Logger LOG = LoggerFactory.getLogger(TelemetryForwarder.class);

  private static final int D2C_MESSAGE_TIMEOUT = 2000; // 2 seconds

  private final Map<UUID, DeviceClient> knownClients = new ConcurrentHashMap<>();

  private final DigitalTwinTopologyProxy cachedDigitalTwinProxy;

  @Slf4j
  protected static class EventCallback implements IotHubEventCallback {

    @Override
    public void execute(final IotHubStatusCode status, final Object context) {
      final Message msg = (Message) context;

      switch (status) {
        case OK:
        case OK_EMPTY:
          log.trace("IoT Hub responded to message {} with status {}", msg.getMessageId(), status);
          break;
        default:
          log.error("IoT Hub responded with an error to message {} with status {}",
              msg.getMessageId(), status);
          break;

      }
    }
  }

  public void sendMessage(final String message, final UUID correlationId, final String hardwareId) {

    final DeviceClient client =
        knownClients
            .computeIfAbsent(
                cachedDigitalTwinProxy.getGatewayIdByHardwareId(hardwareId).orElseThrow(
                    () -> new TopologyElementDoesNotExistException(hardwareId, correlationId)),
                key -> {
                  try {
                    final DeviceClient cl = new DeviceClient(
                        cachedDigitalTwinProxy.getDeviceByDeviceId(key)
                            .orElseThrow(() -> new TopologyElementDoesNotExistException(
                                key.toString(), correlationId))
                            .getConnectionString(),
                        IotHubClientProtocol.AMQPS);
                    cl.open();
                    return cl;
                  } catch (IllegalArgumentException | URISyntaxException | IOException e) {
                    LOG.error("Could not create client", e);
                    return null;
                  }
                });


    final Message msg = new Message(message);
    msg.setContentTypeFinal("application/json");
    msg.setExpiryTime(D2C_MESSAGE_TIMEOUT);

    if (correlationId != null) {
      msg.setCorrelationId(correlationId.toString());
    }

    msg.setProperty("DigitalTwins-SensorHardwareId", hardwareId);
    msg.setProperty("DigitalTwins-Telemetry", "yes");

    final EventCallback callback = new EventCallback();
    if (client != null) {
      client.sendEventAsync(msg, callback, msg);
    }
  }

  @Override
  public void close() throws IOException {
    for (final DeviceClient client : knownClients.values()) {
      client.closeNow();
    }
  }
}
