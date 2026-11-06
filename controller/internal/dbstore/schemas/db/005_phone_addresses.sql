-- +goose Up

-- Every address a paired phone is known to answer at (a phone can have several - wifi and a
-- VPN, say - and DHCP can move it). Ordered by position, the order the user listed them in.
-- phones.base_url stays the one in use: the address the phone last answered at, which is always
-- one of these. Addresses are only a way to reach a phone; its identity is its bearer token.
CREATE TABLE phone_addresses (
    phone_id TEXT NOT NULL REFERENCES phones (id) ON DELETE CASCADE,
    base_url TEXT NOT NULL,
    position INTEGER NOT NULL,
    PRIMARY KEY (phone_id, base_url)
);

INSERT INTO phone_addresses (phone_id, base_url, position)
SELECT id, base_url, 0 FROM phones;

-- +goose Down

DROP TABLE phone_addresses;
