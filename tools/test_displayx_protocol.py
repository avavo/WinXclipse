"""Execute native DisplayX IPC fault injection on Windows using the NDK compiler.

No Android device, Windows SDK, CRT, app package or generated repo binaries needed.
"""
import ctypes
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

class DisplayXProtocolTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        ndk = Path(os.environ['ANDROID_NDK_HOME'])
        compiler = ndk / 'toolchains/llvm/prebuilt/windows-x86_64/bin'
        cls.directory = tempfile.TemporaryDirectory(prefix='displayx-protocol-')
        output = Path(cls.directory.name)
        source = Path(__file__).with_name('displayx_protocol_test.cpp')
        subprocess.run([str(compiler / 'clang++.exe'), '--target=x86_64-pc-windows-msvc',
                        '-ffreestanding', '-fno-exceptions', '-fno-rtti', '-fno-stack-protector',
                        '-std=c++17', '-O1', '-c', str(source), '-o', str(output / 'test.obj')], check=True)
        subprocess.run([str(compiler / 'ld.lld.exe'), '-flavor', 'link', '/dll', '/noentry',
                        '/nodefaultlib', str(output / 'test.obj'), '/out:' + str(output / 'test.dll')], check=True)
        cls.library = ctypes.CDLL(str(output / 'test.dll'))
        cls.library.runProtocolCase.argtypes = [ctypes.c_int]
        cls.library.runProtocolCase.restype = ctypes.c_int

    @classmethod
    def tearDownClass(cls):
        # Windows keeps loaded DLLs open. Explicit unload is safe after all cases.
        import _ctypes
        _ctypes.FreeLibrary(cls.library._handle)
        cls.directory.cleanup()

    def test_native_transport_faults(self):
        names = ['dimensions', 'pool budget', 'version/format/mode', 'short sends',
                 'interrupted send', 'fragmented receive/fd', 'disconnect closes fd',
                 'extra fds closed', 'truncated control closed', 'bad magic closed',
                 'bad version closed', 'fd-free message', 'letterboxed scanout',
                 'cursor requires composition', 'effects require composition',
                 'frame generation requires composition', 'channel override requires composition',
                 'cropping requires composition', 'multiple windows require composition',
                 'invalid scanout geometry', 'terminal fence errors do not spin',
                 'timeout and EINTR remain pending', 'fence completion and syscall failure',
                 'Samsung and Xclipse buffer interoperability', 'other GPU allocation paths unchanged']
        for index, name in enumerate(names):
            with self.subTest(name=name):
                self.assertEqual(0, self.library.runProtocolCase(index))

if __name__ == '__main__':
    unittest.main()
