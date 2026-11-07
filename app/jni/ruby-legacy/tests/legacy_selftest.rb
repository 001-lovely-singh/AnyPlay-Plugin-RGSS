# Run this as a game script (put it as the LAST entry of a test project's script list,
# or paste it into a throw-away RGSS1 project) on the r18 runtime. It prints one line
# per check and a summary through `print`/AnyPlayLog. Not run in CI: it needs the
# real r18 VM, which only exists on a device or emulator.
results = []
check = lambda { |name, ok| results << [name, ok] }

check.call("RUBY_VERSION is 1.8.7 for game code", RUBY_VERSION == "1.8.7")
check.call("RUBY_VERSION_REAL is a 3.x", defined?(RUBY_VERSION_REAL) && RUBY_VERSION_REAL.start_with?("3."))
check.call("RUBY_VERSION is back to 1.8.7 after a require", (require 'set'; true) && RUBY_VERSION == "1.8.7") # swapped back after require

check.call("\"a\"[0] is 97 (byte number)", "a"[0] == 97)
check.call("\"abc\"[-1] is 99", "abc"[-1] == 99)
check.call("\"abc\"[5] is nil", "abc"[5].nil?)
check.call("\"abc\"[0,2] stays a String", "abc"[0, 2] == "ab")
check.call("\"abc\"[1..2] stays a String", "abc"[1..2] == "bc")
check.call("\"a\".slice(0) is 97", "a".slice(0) == 97)

check.call("Object#type", 1.type == Integer)
check.call("Array#nitems", [1, nil, 2].nitems == 2)
check.call("String#each", (a = []; "x\ny".each { |l| a << l }; a == ["x\n", "y"]))
check.call("Hash#index", { :a => 1 }.index(1) == :a)
check.call("Fixnum/Bignum exist", defined?(Fixnum) && defined?(Bignum))
check.call("Thread.critical", (Thread.critical = true; Thread.critical = false; true))

# Library code must NOT get 1.8 behaviour (its file name is not a registered script):
lib_ok = begin
  require 'set'
  require 'stringio'
  io = StringIO.new("ab")
  io.read(1) == "a" && Set.new([1, 2]).include?(2)
rescue Exception
  false
end
check.call("stdlib still works (String#[] gating)", lib_ok)
check.call("eval'd code outside a game script keeps Ruby 3 String#[]", eval('"a"[0]', nil, "(not-a-game-script)") == "a")

bad = results.reject { |_, ok| ok }
msg = results.map { |n, ok| "#{ok ? 'ok  ' : 'FAIL'} #{n}" }.join("\n")
msg << "\nSUMMARY: #{results.size - bad.size}/#{results.size} passed"
msg << "\nlegacy: #{MkxpLegacy.report}" if defined?(MkxpLegacy)
(File.open("legacy_selftest.txt", "w") { |f| f.puts msg }) rescue nil
print msg if bad.any?
