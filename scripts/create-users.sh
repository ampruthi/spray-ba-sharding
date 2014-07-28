#!/bin/bash
for i in {1..100}
do
    http POST http://192.168.99.99:49172/api/users username=foo$i password=bar
    http POST http://192.168.99.99:49172/api/users username=bar$i password=bar
    http POST http://192.168.99.99:49172/api/users username=baz$i password=bar
done