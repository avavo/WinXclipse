The five arm64-v8a Adrenotools/hook libraries are byte-for-byte copies of the
libraries already shipped in `assets/graphics_driver/wrapper-default.tzst`.
They are packaged in nativeLibraryDir so the driver capability probe can use the
same custom-driver loader and hooks from a disposable Android service process.
They do not replace the guest's wrapper or its libraries.

Upstream: https://github.com/bylaws/libadrenotools (BSD-2-Clause).
The API requires legacy JNI library packaging, which this project already enables.
