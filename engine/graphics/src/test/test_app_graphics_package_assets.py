#!/usr/bin/env python

import sys, subprocess, os, platform

HEADER_TEMPLATE = """
#ifndef DM_TEST_APP_GRAPHICS_ASSETS
#define DM_TEST_APP_GRAPHICS_ASSETS
namespace graphics_assets
{
%s
}
#endif
"""

def get_file_contents(file_path):
	f = open(file_path, "rb")
	src = f.read()
	buf = []
	for s in src:
		buf.append(hex(s))
	return buf

def to_spirv(buffer_name, file_path, profile):

	dynamo_home = os.environ['DYNAMO_HOME']

	platform_str = platform.platform().lower()

	if platform_str.startswith("windows"):
		platform_str = "x86_64-win32"
	elif platform_str.startswith("macos"):
		platform_str = "arm64-macos"

	exe = '%s/ext/bin/%s/glslc' % (dynamo_home, platform_str)

	out_path = file_path + '.spv'

	subprocess.call([exe,
		'-fshader-stage='+profile,
		'-fauto-bind-uniforms',
		'-fauto-map-locations',
		'-w', '-o', out_path, file_path])

	buf = get_file_contents(out_path)
	output = "const unsigned char %s[] = {%s};" % (buffer_name, ",".join(buf))

	return output

def to_glsl(buffer_name, file_path):
	buf = get_file_contents(file_path)
	output = "const unsigned char %s[] = {%s};" % (buffer_name, ",".join(buf))
	return output

def write_header(header_path, assets):
	asset_lines = "\n".join(assets)
	header_str = HEADER_TEMPLATE % asset_lines
	f = open(header_path, "w")
	f.write(header_str)
	f.close()

if __name__ == '__main__':
	write_header(
		"test_app_graphics_assets.h",
		[to_glsl("glsl_vertex_program", "test_app_graphics.vs"),
		 to_glsl("glsl_fragment_program", "test_app_graphics.fs"),
		 to_glsl("glsl_compute_program", "test_app_graphics.cp"),
		 to_spirv("spirv_vertex_program", "test_app_graphics.vs", "vert"),
		 to_spirv("spirv_fragment_program", "test_app_graphics.fs", "frag"),
		 to_spirv("spirv_compute_program", "test_app_graphics.cp", "compute")])