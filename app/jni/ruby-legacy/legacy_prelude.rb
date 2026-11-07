# AnyPlay legacy prelude: Ruby 1.8 behaviour on top of the r18 (Ruby 3.1) VM.
#
# Evaluated once, from mkxp_legacy_set_enabled(1), after Ruby is initialised and
# before any game script runs. Every shim sits in its own guarded block: if one of
# them fails the error is recorded in $__mkxp_legacy_errors and the rest still load.
# Shims that need to tell "game code" from "Ruby's own library code" use the C
# helper (String#[]); everything here is either harmless for library code or only
# adds methods that Ruby 3 does not have.

module MkxpLegacy
  ERRORS = []
  STATS  = {}

  def self.guard(name)
    yield
  rescue Exception => e
    ERRORS << "#{name}: #{e.class}: #{e.message}"
    $__mkxp_legacy_errors = ERRORS.join("\n")
  end

  # One short line for AnyPlayLog.
  def self.report
    parts = []
    parts << "shim_errors=#{ERRORS.size}" unless ERRORS.empty?
    n = STATS[:id2ref_fail].to_i
    parts << "id2ref_fail=#{n}" if n > 0
    parts << "version=#{Object.const_defined?(:RUBY_VERSION_REAL) ? RUBY_VERSION_REAL : RUBY_VERSION}"
    parts.join(" ")
  end

  def self.details
    out = ERRORS.dup
    (STATS[:id2ref_where] || []).each do |msg, where|
      out << "id2ref: #{msg} from #{where.first(3).join(' < ')}"
    end
    out
  end
end

# ---- methods Ruby 1.8 had and Ruby 3 removed --------------------------------

MkxpLegacy.guard("Thread.critical") do
  class Thread
    class << self
      unless respond_to?(:critical)
        def critical; false; end
        def critical=(v); v; end
      end
    end
  end
end

MkxpLegacy.guard("Object#type") do
  class Object
    def type; self.class; end unless method_defined?(:type)
  end
end

MkxpLegacy.guard("Array#nitems") do
  class Array
    def nitems; count { |x| !x.nil? }; end unless method_defined?(:nitems)
  end
end

MkxpLegacy.guard("String#each") do
  class String
    unless method_defined?(:each)
      def each(*args, &block)
        return to_enum(:each, *args) unless block
        each_line(*args, &block)
        self
      end
    end
  end
end

MkxpLegacy.guard("Hash#index") do
  class Hash
    def index(value); key(value); end
  end
end

MkxpLegacy.guard("Fixnum/Bignum") do
  v = $VERBOSE
  $VERBOSE = nil
  begin
    Object.const_set(:Fixnum, Integer) unless Object.const_defined?(:Fixnum)
    Object.const_set(:Bignum, Integer) unless Object.const_defined?(:Bignum)
  ensure
    $VERBOSE = v
  end
end

MkxpLegacy.guard("File.exists?") do
  class << File
    alias_method :exists?, :exist? unless respond_to?(:exists?)
  end
  class << Dir
    alias_method :exists?, :exist? unless respond_to?(:exists?)
  end
end

# ---- ObjectSpace._id2ref ----------------------------------------------------
# Ruby 1.8 scripts (Essentials' WeakRef / bitmap cache) call _id2ref and treat
# RangeError as "object is gone". Ruby 3.1 raises the same error, but its GC frees
# unreferenced objects much sooner than 1.8 did. Count the failures and keep the
# first callers so AnyPlayLog shows who is hitting it and how often.
MkxpLegacy.guard("ObjectSpace._id2ref") do
  if ObjectSpace.respond_to?(:_id2ref)
    class << ObjectSpace
      alias_method :__mkxp_legacy_id2ref, :_id2ref
      def _id2ref(id)
        __mkxp_legacy_id2ref(id)
      rescue RangeError => e
        st = MkxpLegacy::STATS
        st[:id2ref_fail] = st[:id2ref_fail].to_i + 1
        if st[:id2ref_fail] <= 3
          (st[:id2ref_where] ||= []) << [e.message.to_s[0, 80], (caller(1, 4) || [])]
        end
        raise
      end
    end
  end
end

# ---- RUBY_VERSION is 1.8.7 for game code, the real one for libraries ---------
# Ruby's own library files check RUBY_VERSION when they are loaded. Give them the
# real values while a `require` is running, and put the spoofed ones back after.
MkxpLegacy.guard("require/RUBY_VERSION") do
  names = [:RUBY_VERSION, :RUBY_PATCHLEVEL, :RUBY_RELEASE_DATE, :RUBY_COPYRIGHT, :RUBY_DESCRIPTION]
  if names.all? { |n| Object.const_defined?(n) && Object.const_defined?("#{n}_REAL") }
    MkxpLegacy.const_set(:NAMES, names)
    MkxpLegacy.const_set(:DEPTH, [0])
    module MkxpLegacy
      def self.swap(to_real)
        v = $VERBOSE
        $VERBOSE = nil
        NAMES.each do |n|
          val = to_real ? Object.const_get("#{n}_REAL") : STATS[:spoof][n]
          Object.send(:remove_const, n)
          Object.const_set(n, val)
        end
      ensure
        $VERBOSE = v
      end
    end
    MkxpLegacy::STATS[:spoof] = {}
    names.each { |n| MkxpLegacy::STATS[:spoof][n] = Object.const_get(n) }
    module Kernel
      alias_method :__mkxp_legacy_require, :require
      def require(name)
        depth = MkxpLegacy::DEPTH
        if depth[0] == 0
          depth[0] = 1
          begin
            MkxpLegacy.swap(true)
            return __mkxp_legacy_require(name)
          ensure
            MkxpLegacy.swap(false)
            depth[0] = 0
          end
        end
        __mkxp_legacy_require(name)
      end
      module_function :require
    end
  end
end
