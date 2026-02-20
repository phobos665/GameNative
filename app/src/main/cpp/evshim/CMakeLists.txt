cmake_minimum_required(VERSION 3.22.1)
project(evshim C)

add_library(evshim SHARED ../extras/evshim.c)
target_compile_options(evshim PRIVATE -O2)
target_link_libraries(evshim log dl)
