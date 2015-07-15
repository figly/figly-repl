# repl-tools

A set of common tools for project-level REPLs

## Tools

### Trace

Instrument every function call in a namespace so that invoking it will log the args and return value to a log file.

You can then inspect the log with `grep` or push logs to a service like Elasticsearch (which you can then query with Kibana or Graylog2)

```
  (trace/trace-all 'mynamespace "log")
```

... will create a series of log files in `./log`. There is an example Logstash configuration for pushing the data to Elasticsearch.

## License

Copyright © 2015 Figly

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
