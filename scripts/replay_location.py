"""Feed the bundled demo route through the Android emulator GPS console."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time


def find_adb():
    """adb from ANDROID_HOME, else the SDK's default place on Windows, macOS or Linux."""
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if home:
        sdk = Path(home)
    elif os.name == "nt":
        sdk = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk"
    elif sys.platform == "darwin":
        sdk = Path.home() / "Library/Android/sdk"
    else:
        sdk = Path.home() / "Android/Sdk"
    return sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("city", choices=["GANGNEUNG", "SEOUL", "BUSAN"])
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--seconds", type=float, default=24)
    parser.add_argument("--point", choices=["start", "arrival"])
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Pleos 에뮬레이터 시리얼을 지정하세요.")
    if args.seconds <= 0:
        parser.error("재생 시간은 양수여야 합니다.")
    root = Path(__file__).resolve().parents[1]
    content = root / "app/src/main/assets/cities.json"
    cities = json.loads(content.read_text(encoding="utf-8"))
    route = next(city["route"] for city in cities if city["cityId"] == args.city)
    adb = find_adb()
    if args.point:
        points = [route[0] if args.point == "start" else route[-1]]
    else:
        points = []
        for start, end in zip(route, route[1:]):
            for step in range(4):
                points.append([start[i] + (end[i] - start[i]) * step / 4 for i in range(2)])
        points.append(route[-1])
    for index, (latitude, longitude) in enumerate(points):
        output = subprocess.check_output(
            [str(adb), "-s", args.serial, "emu", "geo", "fix", str(longitude), str(latitude)], text=True,
        )
        if output.strip() != "OK":
            raise SystemExit(output.strip())
        if index < len(points) - 1:
            time.sleep(args.seconds / max(1, len(points) - 1))
    print("위치 입력 완료. 도착을 확인하려면 Virtual Console에서 기어를 P로 바꾸세요.")


if __name__ == "__main__":
    main()
