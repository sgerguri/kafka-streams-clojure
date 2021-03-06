(ns kafka-streams-clojure.api
  (:import [org.apache.kafka.streams StreamsConfig KafkaStreams KeyValue]
           [org.apache.kafka.streams.kstream Transformer TransformerSupplier KStream KStreamBuilder]
           [org.apache.kafka.streams.processor ProcessorContext]))

(set! *warn-on-reflection* true)

(deftype TransducerTransformer [step-fn ^{:volatile-mutable true} context]
  Transformer
  (init [_ c]
    (set! context c))
  (transform [_ k v]
    (try
      (step-fn context [k v])
      (catch Exception e
        (.printStackTrace e)))
    nil)
  (punctuate [^Transformer this ^long t])
  (close [_]))

(defn- kafka-streams-step
  ([context] context)
  ([^ProcessorContext context [k v]]
   (.forward context k v)
   (.commit context)
   context))

(defn transformer
  "Creates a transducing transformer for use in Kafka Streams topologies."
  [xform]
  (TransducerTransformer. (xform kafka-streams-step) nil))

(defn transformer-supplier
  [xform]
  (reify
    TransformerSupplier
    (get [_] (transformer xform))))

;;; TODO: Wrap fluent build DSL in something more Clojure-y (macro?!?!?)

(defn transduce-kstream
  ^KStream [^KStream kstream xform]
  (.transform kstream (transformer-supplier xform) (into-array String [])))

;;; TODO: Reproduce useful KStream, KTable APIs here as transducers
;;; (i.e. for those not already covered by existing map, filter, etc.)
;;; (e.g. leftJoin, through, etc.)

(comment

  (def xform (comp (filter (fn [[k v]] (string? v)))
                   (map (fn [[k v]] [v k]))
                   (filter (fn [[k v]] (= "foo" v)))))
  (def builder (KStreamBuilder.))
  (def kstream (-> builder
                   (.stream (into-array String ["tset"]))
                   (transduce-kstream xform)
                   (.to "test")))

  (def kafka-streams
    (KafkaStreams. builder (StreamsConfig. {StreamsConfig/APPLICATION_ID_CONFIG    "test-app-id"
                                            StreamsConfig/BOOTSTRAP_SERVERS_CONFIG "localhost:9092"
                                            StreamsConfig/KEY_SERDE_CLASS_CONFIG   org.apache.kafka.common.serialization.Serdes$StringSerde
                                            StreamsConfig/VALUE_SERDE_CLASS_CONFIG org.apache.kafka.common.serialization.Serdes$StringSerde})))
  (.start kafka-streams)

  (import '[org.apache.kafka.clients.producer KafkaProducer ProducerRecord])

  (def producer (KafkaProducer. {"bootstrap.servers" "localhost:9092"
                                 "acks"              "all"
                                 "retries"           "0"
                                 "key.serializer"    "org.apache.kafka.common.serialization.StringSerializer"
                                 "value.serializer"  "org.apache.kafka.common.serialization.StringSerializer"}))

  @(.send producer (ProducerRecord. "tset" "foo" "bar"))

  (.close producer)
  (.close kafka-streams)

  )
