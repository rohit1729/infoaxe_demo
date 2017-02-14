# README #

This README would normally document whatever steps are necessary to get your application up and running.

### What is this repository for? ###
This repository contains demo for testing the performance of ML-lift test server
Also it compares the performance of results from production and test server

### How do I get set up? ###
This project is dependent on jappy framework.


IT has three api endpoints:

1 > /demo/fetch (POST):
  params => widget_id, userid, url, adstyle, size (number of recommendations you want)

2 > /demo/build
  params => inflate=true (this will trigger infalting prediction and truth txt files for all values in input.json)

3 > /demo/score
  params => offset,size (Will return perfect_match and truth_percent for indexes between offset and offset+size)