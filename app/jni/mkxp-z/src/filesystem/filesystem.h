/*
** filesystem.h
**
** This file is part of mkxp.
**
** Copyright (C) 2013 Jonas Kulla <Nyocurio@gmail.com>
**
** mkxp is free software: you can redistribute it and/or modify
** it under the terms of the GNU General Public License as published by
** the Free Software Foundation, either version 2 of the License, or
** (at your option) any later version.
**
** mkxp is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
** GNU General Public License for more details.
**
** You should have received a copy of the GNU General Public License
** along with mkxp.  If not, see <http://www.gnu.org/licenses/>.
*/

#ifndef FILESYSTEM_H
#define FILESYSTEM_H

#include <SDL_rwops.h>
#include <string>
#include <vector>

#include "filesystemImpl.h"

namespace mkxp_fs = filesystemImpl;

struct FileSystemPrivate;
class SharedFontState;

class FileSystem
{
public:
	FileSystem(const char *argv0,
	           bool allowSymlinks);
	~FileSystem();

	void addPath(const char *path, const char *mountpoint = 0, bool reload = false);
    void removePath(const char *path, bool reload = false);

	/* Call these after the last 'addPath()' */
	void createPathCache();
    
    void reloadPathCache();

	/* Scans "Fonts/" and creates inventory of
	 * available font assets */
	void initFontSets(SharedFontState &sfs);

	struct OpenHandler
	{
		/* Try to read and interpret data provided from ops.
		 * If data cannot be parsed, return false, otherwise true.
		 * Can be called multiple times until a parseable file is found.
		 * It's the handler's responsibility to close every passed
		 * ops structure, even when data could not be parsed.
		 * After this function returns, ops becomes invalid, so don't take
		 * references to it. Instead, copy the structure without closing
		 * if you need to further read from it later. */
		virtual bool tryRead(SDL_RWops &ops, const char *ext) = 0;
	};

	void openRead(OpenHandler &handler,
	              const char *filename);

	/* Circumvents extension supplementing */
	void openReadRaw(SDL_RWops &ops,
	                 const char *filename,
	                 bool freeOnClose = false);

	std::string normalize(const char *pathname, bool preferred, bool absolute);

	/* Does not perform extension supplementing */
	bool exists(const char *filename);

	const char *desensitize(const char *filename);

	/* ---- AnyPlay: game played straight from a .zip ------------------------
	 * In this mode the zip is mounted at the root of the virtual file system
	 * and the (writable) save folder is mounted on top of it at the same place
	 * the game's own root folder has inside the zip. [root] = that folder
	 * inside the zip: "" or "Folder/" (trailing slash).
	 * Every path the game uses ("Graphics/x.png", "Data/Scripts.rxdata") is
	 * then looked up under that folder, so a zip with a wrapper folder works
	 * like a normal game folder. Without setZipRoot() nothing changes. */
	void setZipRoot(const char *root);
	bool zipMode() const;
	/* Where to mount the save folder: the zip root ("Folder/"), or 0 for "". */
	const char *zipMountPoint() const;

	/* Questions about the merged tree (save folder + zip), used by the Ruby
	 * File / Dir helpers in zip mode. Paths are game-relative ("Data/x.rxdata"),
	 * lookups are case-insensitive like on Windows (when the path cache is on). */
	/* 0 = does not exist, 1 = file, 2 = directory. [size] gets the file size. */
	int virtualStat(const char *path, long long *size = 0);
	/* Names directly inside [dir] ("" = game root). false if it is no directory. */
	bool virtualList(const char *dir, std::vector<std::string> &out);
	/* Whole file. false if it cannot be opened. */
	bool virtualRead(const char *path, std::string &out);

private:
	FileSystemPrivate *p;
};

extern const Uint32 SDL_RWOPS_PHYSFS;

#endif // FILESYSTEM_H
