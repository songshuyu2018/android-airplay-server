/* config.h for Android — shairport-sync, built by the app CMake */
/* config.h.  Generated from config.h.in by configure.  */
/* config.h.in.  Generated from configure.ac by autoheader.  */

/* Build for AirPlay 2 */
#define CONFIG_AIRPLAY_2 1
/* In-process build: no exit()/fork()/daemonise, see android_start.h */
#define CONFIG_ANDROID 1
#define CONFIG_LIBCONFIG 1
#define CONFIG_POPT 1

/* Needed by the compiler. */
/* #undef CONFIG_ALSA */

/* Include an AO-compatible audio backend. */
/* #undef CONFIG_AO */

/* Include support for using the Apple ALAC Decoder (deprecated) */
/* #undef CONFIG_APPLE_ALAC */

/* Include Avahi-based mDNS support. */
/* #undef CONFIG_AVAHI */

/* Include audio DSP convolution support. */
/* #undef CONFIG_CONVOLUTION */

/* Include the CoreAudio audio backend. */
/* #undef CONFIG_COREAUDIO */

/* DACP client needed for extended metadata support. */
/* #undef CONFIG_DACP_CLIENT */

/* Support the native Shairport Sync D-Bus interface */
/* #undef CONFIG_DBUS_INTERFACE */

/* Include dns_sd mDNS support. */
/* #undef CONFIG_DNS_SD */

/* Include a fake audio backend. */
/* #undef CONFIG_DUMMY */

/* Use 'avahi-publish-service' or 'mDNSPublish' to advertise the service on
   Bonjour/ZeroConf */
/* #undef CONFIG_EXTERNAL_MDNS */

/* Include support for FFmpeg decoders, resampler and utilities. */
#define CONFIG_FFMPEG 1

/* Include support for using the ALAC Decoder by David Hammerton (deprecated)
   */
/* #undef CONFIG_HAMMERTON */

/* Include libdaemon for daemonising in non-systemd systems */
/* #undef CONFIG_LIBDAEMON */

/* Use the mbed TLS libraries for encryption and encoding and decoding */
/* #undef CONFIG_MBEDTLS */

/* Needed by the compiler. */
#define CONFIG_METADATA 1

/* Metadata Hub included. */
#define CONFIG_METADATA_HUB 1

/* Needed by the compiler. */
/* #undef CONFIG_METADATA_MULTICAST */

/* Needed by the compiler. */
#define CONFIG_METADATA_PIPE 1

/* Support the MPRIS standard */
/* #undef CONFIG_MPRIS_INTERFACE */

/* Include a client for MQTT, the Message Queuing Telemetry Transport protocol
   */
/* #undef CONFIG_MQTT */

/* Use the OpenSSL libraries for encryption and encoding and decoding */
#define CONFIG_OPENSSL 1

/* Include an audio backend to output to a unix pipe. */
/* #undef CONFIG_PIPE */

/* Include Pipewire support. */
/* #undef CONFIG_PIPEWIRE */

/* Use the PolarSSL libraries for encryption and encoding and decoding */
/* #undef CONFIG_POLARSSL */

/* Include PulseAudio support. */
/* #undef CONFIG_PULSEAUDIO */

/* Include a sndio-compatible audio backend. */
/* #undef CONFIG_SNDIO */

/* Include support for using the SoX Resampler library for interpolation */
/* #undef CONFIG_SOXR */

/* Include an audio backend to output to standard output (stdout). */
/* #undef CONFIG_STDOUT */

/* Include TinySVCmDNS-based mDNS support (deprecated) */
#define CONFIG_TINYSVCMDNS 1

/* Use the version string produced by running 'git describe --dirty'. */
/* #undef CONFIG_USE_GIT_VERSION_STRING */

/* Define to 1 if using 'alloca.c'. */
/* #undef C_ALLOCA */

/* Hook up special proc to libdaemon to point to this directory */
/* #undef DEFINED_CUSTOM_PID_DIR */

/* Define to 1 if you have 'alloca', as a function or macro. */
#define HAVE_ALLOCA 1

/* Define to 1 if <alloca.h> works. */
#define HAVE_ALLOCA_H 1

/* Define to 1 if you have the <arpa/inet.h> header file. */
#define HAVE_ARPA_INET_H 1

/* Define to 1 if you have the `atexit' function. */
#define HAVE_ATEXIT 1

/* Define to 1 if you have the `clock_gettime' function. */
#define HAVE_CLOCK_GETTIME 1

/* Define to 1 if you have the <fcntl.h> header file. */
#define HAVE_FCNTL_H 1

/* Define to 1 if you have the `fork' function. */
#define HAVE_FORK 1

/* Define to 1 if you have the `gethostname' function. */
#define HAVE_GETHOSTNAME 1

/* Define to 1 if you have the <getopt_long.h> header file. */
/* #undef HAVE_GETOPT_LONG_H */

/* Define to 1 if you have the `inet_ntoa' function. */
#define HAVE_INET_NTOA 1

/* Define to 1 if you have the <inttypes.h> header file. */
#define HAVE_INTTYPES_H 1

/* Define to 1 if you have the `alac' library (-lalac). */
/* #undef HAVE_LIBALAC */

/* Define to 1 if you have the `ao' library (-lao). */
/* #undef HAVE_LIBAO */

/* Define to 1 if you have the `asound' library (-lasound). */
/* #undef HAVE_LIBASOUND */

/* Define to 1 if you have the `avahi-client' library (-lavahi-client). */
/* #undef HAVE_LIBAVAHI_CLIENT */

/* Define to 1 if you have the `avahi-common' library (-lavahi-common). */
/* #undef HAVE_LIBAVAHI_COMMON */

/* Define to 1 if you have the `avcodec' library (-lavcodec). */
/* #undef HAVE_LIBAVCODEC */

/* Define to 1 if you have the `avformat' library (-lavformat). */
/* #undef HAVE_LIBAVFORMAT */

/* Define to 1 if you have the `avutil' library (-lavutil). */
/* #undef HAVE_LIBAVUTIL */

/* Define to 1 if you have the `c' library (-lc). */
/* #undef HAVE_LIBC */

/* Define to 1 if you have the `config' library (-lconfig). */
/* #undef HAVE_LIBCONFIG */

/* Define to 1 if you have the `crypto' library (-lcrypto). */
/* #undef HAVE_LIBCRYPTO */

/* Define to 1 if you have the `daemon' library (-ldaemon). */
/* #undef HAVE_LIBDAEMON */

/* Define to 1 if you have the `gcrypt' library (-lgcrypt). */
/* #undef HAVE_LIBGCRYPT */

/* Define to 1 if you have the `m' library (-lm). */
#define HAVE_LIBM 1

/* Define to 1 if you have the `mbedtls' library (-lmbedtls). */
/* #undef HAVE_LIBMBEDTLS */

/* Define to 1 if you have the `mbedx509' library (-lmbedx509). */
/* #undef HAVE_LIBMBEDX509 */

/* Define to 1 if you have the `mosquitto' library (-lmosquitto). */
/* #undef HAVE_LIBMOSQUITTO */

/* Define to 1 if you have the `pipewire' library (-lpipewire). */
/* #undef HAVE_LIBPIPEWIRE */

/* libplist >= 2.3.0 */
#define HAVE_LIBPLIST_GE_2_3_0 1

/* libplist >= 2.7.0 */
/* #undef HAVE_LIBPLIST_GE_2_7_0 */

/* Define to 1 if you have the `polarssl' library (-lpolarssl). */
/* #undef HAVE_LIBPOLARSSL */

/* Define to 1 if you have the `popt' library (-lpopt). */
/* #undef HAVE_LIBPOPT */

/* Define to 1 if you have the `pthread' library (-lpthread). */
#define HAVE_LIBPTHREAD 1

/* Define to 1 if you have the `pulse' library (-lpulse). */
/* #undef HAVE_LIBPULSE */

/* Define to 1 if you have the `pulse-simple' library (-lpulse-simple). */
/* #undef HAVE_LIBPULSE_SIMPLE */

/* Define to 1 if you have the `rt' library (-lrt). */
#define HAVE_LIBRT 1

/* Define to 1 if you have the `sndfile' library (-lsndfile). */
/* #undef HAVE_LIBSNDFILE */

/* Define to 1 if you have the `sndio' library (-lsndio). */
/* #undef HAVE_LIBSNDIO */

/* Define to 1 if you have the `soxr' library (-lsoxr). */
/* #undef HAVE_LIBSOXR */

/* Define to 1 if you have the `ssl' library (-lssl). */
/* #undef HAVE_LIBSSL */

/* Define to 1 if you have the `swresample' library (-lswresample). */
/* #undef HAVE_LIBSWRESAMPLE */

/* Define to 1 if you have the <limits.h> header file. */
#define HAVE_LIMITS_H 1

/* Define to 1 if you have the <mach/mach.h> header file. */
/* #undef HAVE_MACH_MACH_H */

/* Define to 1 if you have the `memchr' function. */
#define HAVE_MEMCHR 1

/* Define to 1 if you have the `memmove' function. */
#define HAVE_MEMMOVE 1

/* Define to 1 if you have the <memory.h> header file. */
#define HAVE_MEMORY_H 1

/* Define to 1 if you have the `memset' function. */
#define HAVE_MEMSET 1

/* Define to 1 if you have the `mkfifo' function. */
#define HAVE_MKFIFO 1

/* Define to 1 if you have the <netdb.h> header file. */
#define HAVE_NETDB_H 1

/* Define to 1 if you have the <netinet/in.h> header file. */
#define HAVE_NETINET_IN_H 1

/* Define to 1 if you have the `pow' function. */
#define HAVE_POW 1

/* Define to 1 if you have the `select' function. */
#define HAVE_SELECT 1

/* Define to 1 if you have the `socket' function. */
#define HAVE_SOCKET 1

/* Define to 1 if you have the <stdint.h> header file. */
#define HAVE_STDINT_H 1

/* Define to 1 if you have the <stdio.h> header file. */
#define HAVE_STDIO_H 1

/* Define to 1 if you have the <stdlib.h> header file. */
#define HAVE_STDLIB_H 1

/* Define to 1 if you have the `stpcpy' function. */
#define HAVE_STPCPY 1

/* Define to 1 if you have the `strcasecmp' function. */
#define HAVE_STRCASECMP 1

/* Define to 1 if you have the `strchr' function. */
#define HAVE_STRCHR 1

/* Define to 1 if you have the `strdup' function. */
#define HAVE_STRDUP 1

/* Define to 1 if you have the `strerror' function. */
#define HAVE_STRERROR 1

/* Define to 1 if you have the <strings.h> header file. */
#define HAVE_STRINGS_H 1

/* Define to 1 if you have the <string.h> header file. */
#define HAVE_STRING_H 1

/* Define to 1 if you have the `strstr' function. */
#define HAVE_STRSTR 1

/* Define to 1 if you have the `strtol' function. */
#define HAVE_STRTOL 1

/* Define to 1 if you have the `strtoul' function. */
#define HAVE_STRTOUL 1

/* Define to 1 if you have the <syslog.h> header file. */
#define HAVE_SYSLOG_H 1

/* Define to 1 if you have the <sys/ioctl.h> header file. */
#define HAVE_SYS_IOCTL_H 1

/* Define to 1 if you have the <sys/socket.h> header file. */
#define HAVE_SYS_SOCKET_H 1

/* Define to 1 if you have the <sys/stat.h> header file. */
#define HAVE_SYS_STAT_H 1

/* Define to 1 if you have the <sys/time.h> header file. */
#define HAVE_SYS_TIME_H 1

/* Define to 1 if you have the <sys/types.h> header file. */
#define HAVE_SYS_TYPES_H 1

/* Define to 1 if you have the <unistd.h> header file. */
#define HAVE_UNISTD_H 1

/* Define to 1 if you have the `vfork' function. */
#define HAVE_VFORK 1

/* Define to 1 if you have the <vfork.h> header file. */
/* #undef HAVE_VFORK_H */

/* Define to 1 if `fork' works. */
#define HAVE_WORKING_FORK 1

/* Define to 1 if `vfork' works. */
#define HAVE_WORKING_VFORK 1

/* Name of package */
#define PACKAGE "shairport-sync"

/* Define to the address where bug reports for this package should be sent. */
#define PACKAGE_BUGREPORT "4265913+mikebrady@users.noreply.github.com"

/* Define to the full name of this package. */
#define PACKAGE_NAME "shairport-sync"

/* Define to the full name and version of this package. */
#define PACKAGE_STRING "shairport-sync 5.6.2"

/* Define to the one symbol short name of this package. */
#define PACKAGE_TARNAME "shairport-sync"

/* Define to the home page for this package. */
#define PACKAGE_URL ""

/* Define to the version of this package. */
#define PACKAGE_VERSION "5.6.2"

/* If using the C implementation of alloca, define if you know the
   direction of stack growth for your system; otherwise it will be
   automatically deduced at runtime.
	STACK_DIRECTION > 0 => grows toward higher addresses
	STACK_DIRECTION < 0 => grows toward lower addresses
	STACK_DIRECTION = 0 => direction of growth unknown */
/* #undef STACK_DIRECTION */

/* Define to 1 if all of the C90 standard headers exist (not just the ones
   required in a freestanding environment). This macro is provided for
   backward compatibility; new code need not use it. */
#define STDC_HEADERS 1

/* Version number of package */
#define VERSION "5.6.2"

/* Define for Solaris 2.5.1 so the uint32_t typedef from <sys/synch.h>,
   <pthread.h>, or <semaphore.h> is not used. If the typedef were allowed, the
   #define below would cause a syntax error. */
/* #undef _UINT32_T */

/* Define for Solaris 2.5.1 so the uint64_t typedef from <sys/synch.h>,
   <pthread.h>, or <semaphore.h> is not used. If the typedef were allowed, the
   #define below would cause a syntax error. */
/* #undef _UINT64_T */

/* Define for Solaris 2.5.1 so the uint8_t typedef from <sys/synch.h>,
   <pthread.h>, or <semaphore.h> is not used. If the typedef were allowed, the
   #define below would cause a syntax error. */
/* #undef _UINT8_T */

/* Define to `__inline__' or `__inline' if that's what the C compiler
   calls it, or to nothing if 'inline' is not supported under any name.  */
#ifndef __cplusplus
/* #undef inline */
#endif

/* Define to the type of a signed integer type of width exactly 16 bits if
   such a type exists and the standard includes do not define it. */
/* #undef int16_t */

/* Define to the type of a signed integer type of width exactly 32 bits if
   such a type exists and the standard includes do not define it. */
/* #undef int32_t */

/* Define to the type of a signed integer type of width exactly 64 bits if
   such a type exists and the standard includes do not define it. */
/* #undef int64_t */

/* Define as a signed integer type capable of holding a process identifier. */
/* #undef pid_t */

/* Define to `unsigned int' if <sys/types.h> does not define. */
/* #undef size_t */

/* Define to `int' if <sys/types.h> does not define. */
/* #undef ssize_t */

/* Define to the type of an unsigned integer type of width exactly 16 bits if
   such a type exists and the standard includes do not define it. */
/* #undef uint16_t */

/* Define to the type of an unsigned integer type of width exactly 32 bits if
   such a type exists and the standard includes do not define it. */
/* #undef uint32_t */

/* Define to the type of an unsigned integer type of width exactly 64 bits if
   such a type exists and the standard includes do not define it. */
/* #undef uint64_t */

/* Define to the type of an unsigned integer type of width exactly 8 bits if
   such a type exists and the standard includes do not define it. */
/* #undef uint8_t */

/* Define as `fork' if `vfork' does not work. */
/* #undef vfork */

