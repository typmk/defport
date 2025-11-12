(ns defport.util.pagination
  "Cursor-based pagination utilities.

  Provides generic cursor pagination support for protocols that need to page
  large result sets. Uses 'offset-N' cursor format by default, but can be
  extended to support other cursor formats.")

(def ^:private default-page-size 50)

(defn parse-cursor
  "Parse a cursor string to get offset.

  Cursor format: 'offset-N' where N is the offset number.

  Returns integer offset or nil if invalid."
  [cursor]
  (when cursor
    (try
      (when-let [match (re-find #"^offset-(\d+)$" cursor)]
        #?(:clj (Integer/parseInt (second match))
           :cljs (js/parseInt (second match) 10)))
      (catch #?(:clj Exception :cljs :default) _
        nil))))

(defn generate-cursor
  "Generate a cursor string from offset.

  Returns string in format 'offset-N'."
  [offset]
  (str "offset-" offset))

(defn paginate-items
  "Paginate a collection of items based on cursor.

  items: collection to paginate
  cursor: optional cursor string (format: 'offset-N')
  opts: optional map with:
    - :page-size - items per page (default 50)

  Returns map with:
  - :items - paginated slice of items
  - :nextCursor - cursor string for next page (if more items exist)
  - :total - total number of items (optional, if :include-total? true)"
  ([items cursor]
   (paginate-items items cursor nil))
  ([items cursor opts]
   (let [offset (or (parse-cursor cursor) 0)
         page-size (get opts :page-size default-page-size)
         items-vec (vec items)
         total-count (count items-vec)
         end-offset (+ offset page-size)
         has-more (< end-offset total-count)
         paginated-items (subvec items-vec
                                offset
                                (min end-offset total-count))]
     (cond-> {:items paginated-items}
       has-more (assoc :nextCursor (generate-cursor end-offset))
       (:include-total? opts) (assoc :total total-count)))))

(defn paginate-with-metadata
  "Paginate items and include pagination metadata.

  items: collection to paginate
  cursor: optional cursor string
  opts: optional map (see paginate-items)

  Returns map with:
  - :items - paginated slice
  - :pagination - metadata map with:
    - :cursor - current cursor
    - :next-cursor - next page cursor (if exists)
    - :page-size - items per page
    - :offset - current offset
    - :has-more - boolean, true if more pages exist
    - :total - total count (if :include-total? true)"
  ([items cursor]
   (paginate-with-metadata items cursor nil))
  ([items cursor opts]
   (let [offset (or (parse-cursor cursor) 0)
         page-size (get opts :page-size default-page-size)
         result (paginate-items items cursor opts)]
     {:items (:items result)
      :pagination (cond-> {:cursor cursor
                          :page-size page-size
                          :offset offset
                          :has-more (some? (:nextCursor result))}
                    (:nextCursor result) (assoc :next-cursor (:nextCursor result))
                    (:total result) (assoc :total (:total result)))})))
