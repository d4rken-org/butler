# 7z test fixtures

Made with 7-Zip 23.01 (x64), because commons-compress cannot write solid archives or encrypt the
entry list. The password is `secret` wherever one is set.

Source tree (`a.txt` = `alpha\n`, `docs/b.txt` = `bravo bravo bravo\n`,
`docs/nested/c.txt` = `charlie\n`, `docs/numbers.txt` = `seq 1 2000`, `link-to-a` -> `a.txt`):

```
7z a -ms=on                         solid.7z             a.txt docs
7z a -ms=on -psecret -mhe=off       content-encrypted.7z a.txt docs
7z a -ms=on -psecret -mhe=on        header-encrypted.7z  a.txt docs
7z a -m0=Copy -psecret -mhe=off     copy-encrypted.7z    a.txt docs
7z a -snl                           symlink.7z           a.txt link-to-a
7z a -m0=PPMd -psecret -mhe=off     ppmd-encrypted.7z    a.txt docs
7z a -ms=on                         mixed.7z             a.txt
7z a -ms=on -psecret -mhe=off       mixed.7z             docs     # appended: plain block, then encrypted
```
