/*
** anyplay-zipfs.h
**
** AnyPlay: lets Ruby code see the files of a game that is played straight from a
** .zip. Included ONCE by binding-mri.cpp (so no build file has to change).
**
** The engine itself (Bitmap, Audio, load_data, Font, scripts) already reads through
** PhysFS, so it sees the zip. What it does NOT see is Ruby's own File / Dir (File.exist?,
** File.open("Data/x.txt"), Dir.glob("Graphics/*")): those hit the real disk, where only
** the save folder exists. This file adds
**   - System.zip_mode?                  true when a zip game is running
**   - System.zip_stat(path)             nil | 1 (file) | 2 (directory)
**   - System.zip_size(path)             file size or nil
**   - System.zip_children(dir)          names inside dir (Array) or nil
**   - System.zip_read(path)             whole file (binary String) or nil
** and a Ruby layer (ANYPLAY_ZIPFS_RB) that makes File / Dir / IO fall back to them when
** the real disk has no such path. Everything is a no-op for normal folder games.
*/

#ifndef ANYPLAY_ZIPFS_H
#define ANYPLAY_ZIPFS_H

RB_METHOD(zipfsMode) {
    RB_UNUSED_PARAM;
    return shState->fileSystem().zipMode() ? Qtrue : Qfalse;
}

RB_METHOD(zipfsStat) {
    RB_UNUSED_PARAM;
    VALUE path;
    rb_scan_args(argc, argv, "1", &path);
    SafeStringValue(path);
    int t = shState->fileSystem().virtualStat(RSTRING_PTR(path));
    return t == 0 ? Qnil : INT2FIX(t);
}

RB_METHOD(zipfsSize) {
    RB_UNUSED_PARAM;
    VALUE path;
    rb_scan_args(argc, argv, "1", &path);
    SafeStringValue(path);
    long long size = 0;
    int t = shState->fileSystem().virtualStat(RSTRING_PTR(path), &size);
    return t == 1 ? LL2NUM(size) : Qnil;
}

RB_METHOD(zipfsChildren) {
    RB_UNUSED_PARAM;
    VALUE dir;
    rb_scan_args(argc, argv, "1", &dir);
    SafeStringValue(dir);
    std::vector<std::string> names;
    if (!shState->fileSystem().virtualList(RSTRING_PTR(dir), names))
        return Qnil;
    VALUE ary = rb_ary_new();
    for (size_t i = 0; i < names.size(); ++i)
        rb_ary_push(ary, rb_utf8_str_new_cstr(names[i].c_str()));
    return ary;
}

RB_METHOD(zipfsRead) {
    RB_UNUSED_PARAM;
    VALUE path;
    rb_scan_args(argc, argv, "1", &path);
    SafeStringValue(path);
    std::string data;
    if (!shState->fileSystem().virtualRead(RSTRING_PTR(path), data))
        return Qnil;
    return rb_str_new(data.data(), data.size());
}

static void anyplayZipfsBind(VALUE mod) {
    _rb_define_module_function(mod, "zip_mode?", zipfsMode);
    _rb_define_module_function(mod, "zip_stat", zipfsStat);
    _rb_define_module_function(mod, "zip_size", zipfsSize);
    _rb_define_module_function(mod, "zip_children", zipfsChildren);
    _rb_define_module_function(mod, "zip_read", zipfsRead);
}

/* Ruby layer. Installed BEFORE the AnyPlayFastFiles wrappers (they capture
 * File.exist? etc. when they are defined), and only when System.zip_mode? is true. */
static const char *ANYPLAY_ZIPFS_RB = R"RB(
begin
if defined?(System) && System.respond_to?(:zip_mode?) && System.zip_mode?
  require 'stringio'
  module AnyPlayZipFS
    REAL_EXIST = File.method(:exist?)
    # game-relative clean path, or nil when the path is not a plain relative game path
    def self.rel(p)
      p = p.to_path if !p.is_a?(String) && p.respond_to?(:to_path)
      return nil unless p.is_a?(String)
      return nil if p.empty? || p.bytesize > 500
      return nil if p.start_with?("/") || p =~ /\A[A-Za-z]:/
      parts = []
      p.tr("\\", "/").split("/").each do |x|
        next if x.empty? || x == "."
        if x == ".."
          return nil if parts.empty?
          parts.pop
        else
          parts << x
        end
      end
      parts.empty? ? nil : parts.join("/")
    end
    def self.stat(p)
      r = rel(p)
      r ? System.zip_stat(r) : nil
    end
    # a path that is only in the zip (not on the real disk)
    def self.only_zip_file(p)
      r = rel(p)
      return nil unless r
      return nil if REAL_EXIST.call(p)
      System.zip_stat(r) == 1 ? r : nil
    end
    def self.io_for(r, mode)
      data = System.zip_read(r) or return nil
      data = data.dup
      data.force_encoding(mode.to_s.include?("b") ? Encoding::BINARY : Encoding::UTF_8)
      StringIO.new(data)
    end
    def self.read_mode?(m)
      return true if m.nil?
      return !(m & (File::WRONLY | File::RDWR | File::APPEND | File::CREAT | File::TRUNC) != 0) if m.is_a?(Integer)
      !(m.to_s =~ /[wa+]/)
    end
    def self.children(dir)
      r = dir.to_s.empty? || dir.to_s == "." ? "" : rel(dir)
      return nil if r.nil?
      System.zip_children(r)
    end
    # Dir.glob over the zip (names compared case-insensitively, like Windows)
    MAGIC = /[*?\[{]/
    def self.walk(base, segs, flags, out, depth = 0)
      return if segs.empty? || depth > 30
      seg = segs[0]
      rest = segs[1..-1]
      if seg == "**"
        walk(base, rest, flags, out, depth + 1) unless rest.empty?
        (children(base) || []).each do |n|
          path = base.empty? ? n : "#{base}/#{n}"
          walk(path, segs, flags, out, depth + 1) if System.zip_stat(path) == 2
        end
      elsif seg !~ MAGIC
        (children(base) || []).each do |n|
          next unless n.casecmp(seg) == 0
          path = base.empty? ? n : "#{base}/#{n}"
          if rest.empty?
            out << path
          elsif System.zip_stat(path) == 2
            walk(path, rest, flags, out, depth + 1)
          end
        end
      else
        (children(base) || []).each do |n|
          next unless File.fnmatch(seg, n, File::FNM_EXTGLOB | File::FNM_CASEFOLD | (flags & File::FNM_DOTMATCH))
          path = base.empty? ? n : "#{base}/#{n}"
          if rest.empty?
            out << path
          elsif System.zip_stat(path) == 2
            walk(path, rest, flags, out, depth + 1)
          end
        end
      end
    end
    def self.glob(pattern, flags)
      r = rel(pattern.to_s.sub(/\/+\z/, ""))
      return [] unless r
      out = []
      walk("", r.split("/"), flags, out)
      out
    end
  end

  class << File
    [:exist?, :file?, :directory?].each do |meth|
      real = File.method(meth)
      want = meth == :exist? ? nil : (meth == :file? ? 1 : 2)
      define_method(meth) do |path|
        return true if real.call(path)
        t = AnyPlayZipFS.stat(path)
        t && (want.nil? || t == want) ? true : false
      end
    end
    alias_method :exists?, :exist?

    real_size = File.method(:size)
    define_method(:size) do |path|
      begin
        real_size.call(path)
      rescue Errno::ENOENT
        r = AnyPlayZipFS.rel(path)
        s = r && System.zip_size(r)
        raise unless s
        s
      end
    end

    real_open = File.method(:open)
    open_zip = lambda do |a, kw, blk|
      path = a[0]
      mode = a[1]
      mode = kw[:mode] if mode.nil?
      if AnyPlayZipFS.read_mode?(mode)
        r = AnyPlayZipFS.only_zip_file(path)
        if r
          io = AnyPlayZipFS.io_for(r, mode)
          if io
            return io unless blk
            begin
              return blk.call(io)
            ensure
              io.close unless io.closed?
            end
          end
        end
      else
        # writing into a folder that exists only inside the zip: create it on the real disk
        begin
          return kw.empty? ? real_open.call(*a, &blk) : real_open.call(*a, **kw, &blk)
        rescue Errno::ENOENT
          dir = File.dirname(path.to_s)
          raise if dir == "." || AnyPlayZipFS.stat(dir) != 2
          require 'fileutils'
          FileUtils.mkdir_p(dir)
          return kw.empty? ? real_open.call(*a, &blk) : real_open.call(*a, **kw, &blk)
        end
      end
      kw.empty? ? real_open.call(*a, &blk) : real_open.call(*a, **kw, &blk)
    end
    define_method(:open) { |*a, **kw, &b| open_zip.call(a, kw, b) }
    define_method(:new) { |*a, **kw, &b| open_zip.call(a, kw, b) }
  end

  class << IO
    [:read, :binread, :readlines, :foreach].each do |meth|
      real = IO.method(meth)
      define_method(meth) do |*a, **kw, &b|
        r = a[0].is_a?(String) || a[0].respond_to?(:to_path) ? AnyPlayZipFS.only_zip_file(a[0]) : nil
        if r
          data = System.zip_read(r)
          if data
            data = data.dup
            data.force_encoding(meth == :binread ? Encoding::BINARY : Encoding::UTF_8)
            case meth
            when :read, :binread
              len = a[1]
              off = a[2] || 0
              data = data.byteslice(off, len || data.bytesize) || "" if len || off != 0
              return data
            when :readlines
              return StringIO.new(data).readlines(*a[1..-1])
            when :foreach
              return StringIO.new(data).each_line(*a[1..-1], &b) if b
              return StringIO.new(data).each_line(*a[1..-1])
            end
          end
        end
        kw.empty? ? real.call(*a, &b) : real.call(*a, **kw, &b)
      end
    end
  end

  class << Dir
    real_exist = Dir.method(:exist?)
    define_method(:exist?) { |path| real_exist.call(path) || AnyPlayZipFS.stat(path) == 2 }
    alias_method :exists?, :exist?

    real_children = Dir.method(:children)
    define_method(:children) do |dir, *rest|
      real = (real_children.call(dir, *rest) rescue nil)
      zip = AnyPlayZipFS.children(dir)
      raise Errno::ENOENT, dir.to_s if real.nil? && zip.nil?
      ((real || []) + (zip || [])).uniq
    end
    define_method(:entries) { |dir, *rest| [".", ".."] + children(dir) }
    define_method(:each_child) do |dir, *rest, &b|
      list = children(dir)
      return list.each unless b
      list.each(&b)
      nil
    end
    define_method(:foreach) do |dir, *rest, &b|
      list = entries(dir)
      return list.each unless b
      list.each(&b)
      nil
    end

    real_glob = Dir.method(:glob)
    define_method(:glob) do |pattern, flags = 0, **kw, &b|
      pats = pattern.is_a?(Array) ? pattern : [pattern]
      res = []
      pats.each do |pt|
        res.concat(kw.empty? ? real_glob.call(pt, flags) : real_glob.call(pt, flags, **kw))
        res.concat(AnyPlayZipFS.glob(pt, flags)) unless kw[:base]
      end
      res.uniq!
      return res unless b
      res.each(&b)
      nil
    end
    define_method(:[]) { |*pats, **kw| glob(pats.flatten, 0, **kw) }
  end
end
rescue Exception => e
  (System.puts("AnyPlay zip shim failed: #{e.class}: #{e.message}") rescue nil)
end
)RB";

#endif // ANYPLAY_ZIPFS_H
