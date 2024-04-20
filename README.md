# Magic Home Flasher (BL602)

## Introduction
Magic Home Flasher is an Android application designed to flash Magic Home devices equipped with a BL602 chip using the OpenBeken firmware available from the OpenBeken_App project on GitHub. This tool simplifies the process of updating devices to use open-source firmware, enhancing their functionality and customizability.

## Features
- **Simple Device Updating**: Select and flash devices with new firmware directly over the air.
- **Open Source**: Leverage and contribute to the open-source community.

## Getting Started
These instructions will guide you through the setup and operation of Magic Home Flasher.

### Prerequisites
- An Android device running Android 29 or higher.
- Access to a Wi-Fi network.
- A Magic Home device with a BL602 chip within range.

### Installation
1. Download the latest release of Magic Home Flasher from the [Releases](https://github.com/yourgithub/MagicHomeFlasher/releases) section on GitHub.
2. Install the APK on your Android device. You may need to enable installation from unknown sources in your device settings.

### Usage
1. Open the Magic Home Flasher app.
2. Tap on the **Scan Wifi Devices** button to discover available Wi-Fi networks.
3. Look for networks named like `LEDnetXXXXXX`. These are typically broadcast by Magic Home devices.
4. Select the network corresponding to the device you wish to flash.
5. The app will automatically handle the OTA update process by serving the firmware over an internal HTTP server.
6. Once the device downloads and applies the firmware, it will reboot automatically, running the new OpenBeken system.

## Firmware
The firmware used by Magic Home Flasher is provided by the OpenBK7231T_App project. You can find more information and contribute to the firmware development at [OpenBeken_App GitHub Repository](https://github.com/openshwprojects/OpenBK7231T_App).

## TODO

The following features are planned for future releases of Magic Home Flasher to enhance its functionality and user experience:

- **Progress Bar Integration**: Implement a progress bar to provide real-time feedback during the firmware installation process. This will help users understand the current status of the update and estimate the time remaining until completion.

- **Wi-Fi Settings Configuration**: Add the capability to change the SSID and password of the Magic Home device directly from the app. This feature will allow users to manage their device's network settings easily without needing additional tools.

- **Automatic Firmware Updates**: Integrate functionality to automatically fetch the latest version of OpenBeken firmware from the GitHub repository. This will ensure that users always have access to the latest features and security updates without manually checking for new releases.

These enhancements aim to streamline the user experience and expand the functionality of Magic Home Flasher, making it a more robust and convenient tool for updating Magic Home devices.

## Contributing
Contributions to Magic Home Flasher are welcome! Whether it's reporting issues, submitting fixes, or proposing new features, your help is appreciated.

### How to Contribute
1. Fork the repository.
2. Create your feature branch (`git checkout -b feature/AmazingFeature`).
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`).
4. Push to the branch (`git push origin feature/AmazingFeature`).
5. Open a new Pull Request.

## License
This project is licensed under the MIT License - see the [LICENSE.md](LICENSE.md) file for details.

## Acknowledgments
- Thanks to the OpenBK7231T_App project for providing the firmware.
- Community contributions that help improve this tool.

## Support
For support, email contact@yourdomain.com or open an issue in the GitHub repository.

---
For more information on how to use Magic Home Flasher, please refer to the wiki or contact support.
