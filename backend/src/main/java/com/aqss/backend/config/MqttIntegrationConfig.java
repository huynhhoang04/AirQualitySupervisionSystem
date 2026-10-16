package com.aqss.backend.config;

import com.aqss.backend.mqtt.MqttInboundHandler;
import lombok.RequiredArgsConstructor;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

/**
 * MQTT wiring: one inbound adapter subscribes to the three device topics and dispatches through
 * {@link MqttInboundHandler}; a dedicated outbound handler publishes commands back to nodes.
 *
 * <p>The adapter and outbound handler are only created when {@code mqtt.enabled=true} (default),
 * so the backend can still boot — REST, control loop and persistence fully functional — when no
 * broker is reachable (local development, tests).
 */
@Configuration
@RequiredArgsConstructor
public class MqttIntegrationConfig {

    public static final String MQTT_INBOUND_CHANNEL = "mqttInboundChannel";
    public static final String MQTT_OUTBOUND_CHANNEL = "mqttOutboundChannel";

    private final MqttProperties properties;

    @Bean(name = MQTT_INBOUND_CHANNEL)
    public MessageChannel mqttInboundChannel() {
        return new DirectChannel();
    }

    @Bean(name = MQTT_OUTBOUND_CHANNEL)
    public MessageChannel mqttOutboundChannel() {
        return new DirectChannel();
    }

    @Bean
    public MqttPahoClientFactory mqttClientFactory() {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        MqttConnectOptions options = new MqttConnectOptions();
        options.setServerURIs(new String[]{properties.brokerUrl()});
        options.setUserName(properties.username());
        options.setPassword(properties.password().toCharArray());
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        factory.setConnectionOptions(options);
        return factory;
    }

    /** Outbound: publishes {@code set_actuator} / {@code display_warning} / {@code set_space} to nodes. */
    @Bean
    @ServiceActivator(inputChannel = MQTT_OUTBOUND_CHANNEL)
    @ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = true)
    public MessageHandler mqttOutboundHandler() {
        MqttPahoMessageHandler handler =
                new MqttPahoMessageHandler(properties.clientId() + "-pub", mqttClientFactory());
        handler.setAsync(true);
        handler.setDefaultQos(properties.qos());
        handler.setCompletionTimeout(5000);
        handler.setDefaultRetained(false);
        return handler;
    }

    @Bean
    @ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = true)
    public MqttPahoMessageDrivenChannelAdapter mqttInboundAdapter() {
        MqttPahoMessageDrivenChannelAdapter adapter = new MqttPahoMessageDrivenChannelAdapter(
                properties.clientId(), mqttClientFactory(),
                properties.topics().telemetry(),
                properties.topics().status(),
                properties.topics().actuatorState());
        adapter.setQos(properties.qos());
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setCompletionTimeout(5000);
        adapter.setOutputChannel(mqttInboundChannel());
        return adapter;
    }

    @Bean
    @ServiceActivator(inputChannel = MQTT_INBOUND_CHANNEL)
    @ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = true)
    public MessageHandler mqttInboundMessageHandler(MqttInboundHandler handler) {
        return handler;
    }
}
