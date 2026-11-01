"""Run the real audio consumer with injected pause, wake, underrun and stop events."""
import ctypes
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

class AudioPacerTests(unittest.TestCase):
    def test_consumer_lifecycle(self):
        compiler=Path(os.environ['ANDROID_NDK_HOME'])/'toolchains/llvm/prebuilt/windows-x86_64/bin'
        with tempfile.TemporaryDirectory(prefix='audio-pacer-') as folder:
            output=Path(folder)
            subprocess.run([str(compiler/'clang++.exe'),'--target=x86_64-pc-windows-msvc',
                '-ffreestanding','-fno-exceptions','-fno-rtti','-fno-stack-protector','-std=c++17','-O1','-c',
                str(Path(__file__).with_name('audio_pacer_test.cpp')),'-o',str(output/'test.obj')],check=True)
            subprocess.run([str(compiler/'ld.lld.exe'),'-flavor','link','/dll','/noentry','/nodefaultlib',
                str(output/'test.obj'),'/out:'+str(output/'test.dll')],check=True)
            library=ctypes.CDLL(str(output/'test.dll'))
            library.runAudioCase.argtypes=[ctypes.c_int];library.runAudioCase.restype=ctypes.c_int
            try:
                for index,name in enumerate(['paused with queued audio','spurious wake and resume',
                        'underrun resets clock','stop while empty','pause during playback','already stopped']):
                    with self.subTest(name=name):self.assertEqual(0,library.runAudioCase(index))
            finally:
                import _ctypes
                _ctypes.FreeLibrary(library._handle)

if __name__=='__main__':unittest.main()
