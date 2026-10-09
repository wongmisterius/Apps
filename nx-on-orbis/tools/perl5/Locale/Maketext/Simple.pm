# Minimal stand-in for Locale::Maketext::Simple (missing from Git for Windows' perl); enough for
# Params::Check / IPC::Cmd, which OpenSSL's Configure loads.
package Locale::Maketext::Simple;
sub import {
    my $caller = caller;
    no strict 'refs';
    *{"${caller}::loc"} = sub { my $s = shift; my @a = @_; $s =~ s/\[_(\d+)\]/$a[$1-1]/g; $s };
    *{"${caller}::loc_lang"} = sub { 1 };
}
1;
