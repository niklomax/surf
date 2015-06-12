---
layout: page
title:  surf - Simulating Urban Flows
tagline: Understanding Urban Movements through Big Data and Social Simulation
---
{% include JB/setup %}

This is the website for <a href="nickmalleson.co.uk/">Nick Malleson's</a> _surf_ research project, funded by <a href="http://www.esrc.ac.uk/funding-and-guidance/funding-opportunities/15938/future-research-leaders.aspx">ESRC Future Research Leaders</a> scheme. 

## Introduction

The aim of this research is to fundamentally alter our understanding of daily urban movement patterns through a combination of 'big data' analysis and cutting-edge computer simulation. It will develop new methods to produce data that will help us to address key issues in crime and health.

### Background 









    
## Posts

<ul class="posts">
  {% for post in site.posts %}
    <li><span>{{ post.date | date_to_string }}</span> &raquo; <a href="{{ BASE_PATH }}{{ post.url }}">{{ post.title }}</a></li>
  {% endfor %}
</ul>

