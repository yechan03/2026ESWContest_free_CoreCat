/*
 * MQTT_Example.h
 *
 *  Created on: Dec 6, 2025
 *      Author: controllerstech
 */

#ifndef ETHERNET_W550_MQTT_EXAMPLE_H_
#define ETHERNET_W550_MQTT_EXAMPLE_H_

#include <stdint.h>

// --------------------------------------------------------------
//  Public API – call these from main.c
// --------------------------------------------------------------

int mqtt_network_init(void);

int mqtt_connect_broker(void);

void mqtt_publish(char *topic, const char* payload);

int mqtt_subscribe(char *topic);

void mqtt_yield(void);

void MQTT_Example(void);


#endif /* ETHERNET_W550_MQTT_EXAMPLE_H_ */
