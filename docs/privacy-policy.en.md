# Huimao Map Privacy Policy

**Effective date: August 31, 2026**  
**Applicable versions: Huimao Map 1.1.7 and later versions**

This Privacy Policy applies to the Huimao Map Android mobile application, its Wear OS companion application, and the accompanying WeChat location redirection plugins (collectively, the “Application”).

Huimao Map is a map and navigation tool. The project does not provide user account registration, operate a backend server to store location history, search history, or personal profiles, or sell users’ personal information.

## 1. Information we process

To provide maps, location, search, route planning, navigation, and watch navigation display features, the Application may process the following information locally on the device or through integrated third-party SDKs:

- **Location information:** current location, direction, speed, location accuracy, and location time, used to show the current position, search nearby places, plan routes, and provide navigation.
- **Search and route information:** searched places, origin, destination, waypoints, route results, navigation instructions, road names, remaining distance, and estimated remaining time.
- **App settings and local data:** Baidu Maps Android AK, home/work addresses, navigation preferences, application settings, and necessary cached data.
- **Device and runtime information:** application version, Android version, device capabilities, network status, and crash or error information, used for compatibility and troubleshooting.
- **Wear OS navigation state:** when the Wear OS companion app is used, the mobile app synchronizes necessary navigation information to the paired watch, such as navigation status, the next instruction, turn distance, current road, remaining distance, and remaining time.

The Application does not actively collect names, email addresses, telephone numbers, contacts, SMS messages, photos, payment information, or other personal information unrelated to map and navigation functions.

## 2. How information is used

We use the information described above only to:

- provide maps, location, place search, route planning, and navigation on the phone;
- process WeChat location redirection and open navigation routes when requested by the user;
- display the mobile navigation state in the Wear OS companion app;
- save addresses, settings, and necessary cache data locally on the device;
- troubleshoot errors and improve stability, compatibility, and user experience; and
- comply with lawful requests where required by law or necessary to protect users or the public.

## 3. Wear OS companion app

The Wear OS app is a non-standalone companion app for the mobile Huimao Map application and must be used with a paired phone.

- Navigation information is transferred between the phone and paired watch through the Google Play services Wearable Data Layer.
- The project does not operate a server that relays or stores this synchronized data.
- The watch app does not provide independent route planning, place search, or standalone map navigation.
- Users can stop navigation, disconnect the devices, clear app data, or uninstall the app to stop future synchronization.

## 4. WeChat location redirection plugins

The WeChat location redirection plugins process location parameters only when the user actively opens or shares a location. The plugins convert those parameters into a deep link that Huimao Map can handle.

The plugins do not provide an account system, operate a backend server to store location data, or use location data for advertising or marketing. Each plugin uses the package name of its corresponding map application and cannot be installed alongside that official application.

## 5. Third-party services

The Application uses Baidu Maps, Baidu Location, and Baidu Navigation SDKs to provide maps, location, search, route planning, and navigation. When these features are used, location, search, route, device, and network information may be processed by Baidu according to its own privacy policy and SDK personal-information processing rules.

Wear OS synchronization uses Google Play services Wearable Data Layer. Google may process necessary connection and service data according to its applicable policies while devices are paired and data is transferred.

Please also review the privacy policies of the relevant third-party service providers. Third-party SDK data processing is controlled by the corresponding service providers.

## 6. Storage, sharing, and security

- Application settings, shortcut addresses, and some cached data are primarily stored locally on the user’s devices.
- The project does not operate a backend service that stores location history, search history, or account profiles.
- Except for processing required to use the map, location, navigation, and device-connection services described above, we do not sell, rent, or share personal information with advertisers.
- Users can revoke permissions, clear app data, or uninstall the Application through Android system settings to remove locally stored data.

## 7. Permissions

The actual permissions requested depend on the installed package and Android system authorization prompts. The Application may use:

| Permission or capability | Purpose |
| --- | --- |
| Precise or approximate location | Location, nearby search, route planning, and navigation |
| Internet and network state | Access to maps, search, route planning, and navigation services |
| Bluetooth scanning | Device connection or location-related capabilities where supported by the system |
| Foreground service, notifications, and wake lock | Keep navigation active and show navigation status |
| Wear OS device connection | Synchronize necessary navigation state to a paired watch |

If location or network access is denied, map, search, route-planning, and navigation features may not work correctly.

## 8. Children’s privacy

The Application is not designed for children. We do not knowingly collect children’s personal information. If a parent or guardian believes that a child has provided personal information while using the Application or a related third-party service, please contact us through the channel below.

## 9. Policy updates

This policy may be updated when application features, third-party SDKs, or data-processing practices change materially. Updated versions will be published in this GitHub repository and identified by the effective date shown on this page.

## 10. Contact

For questions about this Privacy Policy or information processing, please contact the project maintainer through GitHub Issues:

https://github.com/huimao28/huimao-map/issues
