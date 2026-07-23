# PD Voltage Tool

Lightweight Android USB HID controller for PD trigger devices.

- VID: `A016`
- APP PID: Std `0404`, Nano `0104`
- Bootloader PID: Std `0405`, Nano `0105`
- Android 8.0+
- Voltage presets, system controls, USB status, and 16 UI languages
- Fetches the latest matching firmware and flashes only after reconnecting and granting USB permission in Bootloader mode

Download the APK from [Releases](https://github.com/RyuhungLiu/pd-voltage-tool-android/releases).

```powershell
.\gradlew.bat :app:assembleDebug
```
