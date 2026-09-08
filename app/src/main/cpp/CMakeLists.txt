cmake_minimum_required(VERSION 3.22.1)
project(jobmaker_llm C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---------------------------------------------------------------------------
# Optimisation du code natif, y compris en variante debug de l'application.
#
# L'APK distribue est un build debug (assembleDebug) : plus gros, mais sans
# minification R8, donc sans risque qu'une classe atteinte par reflexion soit
# supprimee par erreur. Le probleme est que Gradle demande alors a CMake
# CMAKE_BUILD_TYPE=Debug, et la chaine du NDK compile TOUT le code natif en
# -O0. Sur ggml, cela divise la vitesse d'inference par plusieurs dizaines :
# les noyaux SIMD sont bien la, mais sans inlining, sans vectorisation des
# boucles qui les entourent et sans allocation de registres correcte.
#
# Mesure sur un Snapdragon 8 Elite avec Qwen3 4B Q4 avant correction :
# 0,9 token/s en lecture de prompt et 0,45 token/s en redaction, soit environ
# cinquante fois moins que ce que la machine sait faire.
#
# On remplace donc les jeux de drapeaux par configuration. Ils doivent etre
# fixes AVANT que llama.cpp ne soit ajoute, pour que ses cibles les recoivent.
# A noter : -O3 dans cppFlags cote Gradle ne suffisait pas, car il alimente
# CMAKE_CXX_FLAGS, place avant CMAKE_CXX_FLAGS_DEBUG sur la ligne de commande,
# donc ecrase par le -O0 qui suit. Et le coeur de ggml est en C, pas en C++.
# ---------------------------------------------------------------------------
if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

foreach(conf DEBUG RELWITHDEBINFO MINSIZEREL RELEASE)
    set(CMAKE_C_FLAGS_${conf}   "-O3 -DNDEBUG")
    set(CMAKE_CXX_FLAGS_${conf} "-O3 -DNDEBUG")
endforeach()

# Ceinture et bretelles : add_compile_options place ses drapeaux APRES les
# CMAKE_<LANG>_FLAGS_<CONFIG> sur la ligne de commande. Comme c'est le dernier
# -O rencontre qui gagne, -O3 s'impose meme si l'outillage reinjecte un -O0
# plus tard. A declarer avant llama.cpp pour que ses cibles en heritent.
add_compile_options(-O3)

message(STATUS "JobMaker/natif : CMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}")
message(STATUS "JobMaker/natif : C_FLAGS=${CMAKE_C_FLAGS} | par config="
               "${CMAKE_C_FLAGS_${CMAKE_BUILD_TYPE}} | plus add_compile_options(-O3)")

if (POLICY CMP0135)
    cmake_policy(SET CMP0135 NEW)
endif()

# ---------------------------------------------------------------------------
# Parametres injectes par Gradle (voir gradle.properties)
# ---------------------------------------------------------------------------
if (NOT DEFINED JOBMAKER_LLAMA_TAG)
    set(JOBMAKER_LLAMA_TAG "b6100")
endif()
if (NOT DEFINED JOBMAKER_ARM_ARCH)
    set(JOBMAKER_ARM_ARCH "armv8.2-a+dotprod+i8mm+fp16")
endif()
if (NOT DEFINED JOBMAKER_OPENCL)
    set(JOBMAKER_OPENCL OFF)
endif()

message(STATUS "JobMaker : llama.cpp tag=${JOBMAKER_LLAMA_TAG} arm=${JOBMAKER_ARM_ARCH} opencl=${JOBMAKER_OPENCL}")

# ---------------------------------------------------------------------------
# Recuperation de llama.cpp
#
# On telecharge l'archive d'une release figee plutot que d'utiliser un
# sous-module git : pas d'etat a initialiser, et le cache CMake evite de
# retelecharger a chaque build. Le code JNI ci-dessous est ecrit pour l'API
# de ce tag ; changer le tag peut demander d'ajuster llama_jni.cpp.
# ---------------------------------------------------------------------------
include(FetchContent)

# Options llama.cpp : on ne veut que la bibliotheque, rien d'autre.
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON     OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL             OFF CACHE BOOL "" FORCE)

# Options ggml : OpenMP est instable sous le NDK, llamafile accelere les
# matmuls quantifies, et on force le jeu d'instructions ARM (pas de detection
# automatique possible en compilation croisee).
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE         ON  CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS       OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES    OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "${JOBMAKER_ARM_ARCH}" CACHE STRING "" FORCE)

if (JOBMAKER_OPENCL)
    # Backend GPU Adreno. Experimental : necessite les en-tetes OpenCL et un
    # ICD loader, qu'on recupere aussi par FetchContent.
    FetchContent_Declare(opencl_headers
        URL https://github.com/KhronosGroup/OpenCL-Headers/archive/refs/tags/v2024.10.24.tar.gz)
    FetchContent_MakeAvailable(opencl_headers)

    FetchContent_Declare(opencl_icd
        URL https://github.com/KhronosGroup/OpenCL-ICD-Loader/archive/refs/tags/v2024.10.24.tar.gz)
    set(OPENCL_ICD_LOADER_HEADERS_DIR "${opencl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)
    FetchContent_MakeAvailable(opencl_icd)

    set(GGML_OPENCL           ON  CACHE BOOL "" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS   ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
endif()

FetchContent_Declare(llama_cpp
    URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/${JOBMAKER_LLAMA_TAG}.tar.gz
)
FetchContent_MakeAvailable(llama_cpp)

# ---------------------------------------------------------------------------
# Notre bibliotheque JNI
# ---------------------------------------------------------------------------
add_library(jobmaker_llm SHARED llama_jni.cpp)

target_compile_definitions(jobmaker_llm PRIVATE JOBMAKER_LLAMA_TAG="${JOBMAKER_LLAMA_TAG}")

target_link_libraries(jobmaker_llm
    PRIVATE
        llama
        ggml
        android
        log
)

# L'optimisation est fixee plus haut, pour toutes les cibles y compris ggml.
# Ici, seule la visibilite nous concerne : les symboles JNI sont exportes
# explicitement par JNIEXPORT.
target_compile_options(jobmaker_llm PRIVATE -fvisibility=hidden)
