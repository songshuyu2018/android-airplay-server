#ifndef AUDIO_LIGHT_CONFIG_H
#define AUDIO_LIGHT_CONFIG_H
// Shared C/JNI contract. Changes are applied by the serialized spectrum reader.
typedef struct AudioLightConfig {
    float sensitivity;
    float midWeight;
    float highWeight;
    int intervalMs;
    int releaseMs;
} AudioLightConfig;
#endif
