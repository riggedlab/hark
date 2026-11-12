"""读取板子串口输出。用法: python3 serial_read.py [秒数]"""
import glob, sys, time, serial
port = sorted(glob.glob("/dev/cu.usbmodem*"))[0]
dur = float(sys.argv[1]) if len(sys.argv) > 1 else 10
with serial.Serial(port, 115200, timeout=0.2) as s:
    end = time.time() + dur
    while time.time() < end:
        line = s.readline()
        if line:
            print(line.decode("utf-8", "replace").rstrip(), flush=True)
