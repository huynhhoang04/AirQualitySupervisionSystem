#pragma once

/**
 * Raise the setup SoftAP + captive portal.
 *
 * retryStation = true  : the node keeps trying the configured Wi-Fi in the background and returns
 *                        as soon as it joins (used when the link is down).
 * retryStation = false : manual setup (button/serial) — the AP stays until the form is saved.
 * Never returns when the form is saved (the node reboots).
 */
void provisioningRun(bool retryStation);
