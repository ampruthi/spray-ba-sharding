#!/bin/bash
for i in {1..100}
do
    http -a foo$i:bar http://192.168.99.99:49172/secure
    http -a bar$i:bar http://192.168.99.99:49172/secure
    http -a baz$i:bar http://192.168.99.99:49172/secure
done