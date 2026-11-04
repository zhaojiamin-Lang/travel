ALTER TABLE trip_request
    ADD COLUMN interest_tags VARCHAR(1024);

ALTER TABLE trip_request
    ADD COLUMN excluded_poi_ids VARCHAR(1024);
