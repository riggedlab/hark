/* BLE：加密音频服务。按键只打开配对窗口，新配对成功才替换旧密钥。 */
#include <zephyr/kernel.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/hci.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/settings/settings.h>
#include <zephyr/sys/atomic.h>
#include <zephyr/sys/byteorder.h>
#include <string.h>
#include "app.h"

#define RIG_UUID(n) BT_UUID_128_ENCODE(0x5a1d0000 | (n), 0x7e3c, 0x4b8a, 0x9f00, 0x524947000000)
#define PAIR_WINDOW_MS 60000
#define UNENCRYPTED_MS 15000
#define DEBOUNCE_MS 30 /* 机械按键稳定 30ms 后采样；长按只触发一次 */

static const struct bt_uuid_128 svc_uuid   = BT_UUID_INIT_128(RIG_UUID(1));
static const struct bt_uuid_128 audio_uuid = BT_UUID_INIT_128(RIG_UUID(2));
static const struct bt_uuid_128 batt_uuid  = BT_UUID_INIT_128(RIG_UUID(3));
static const struct gpio_dt_spec led = GPIO_DT_SPEC_GET(DT_ALIAS(led0), gpios);
static const struct gpio_dt_spec button = GPIO_DT_SPEC_GET(DT_ALIAS(sw0), gpios);
static struct gpio_callback button_cb;
static struct k_spinlock state_lock;
static struct bt_conn *cur_conn;
static int64_t pairing_deadline, security_deadline;
static bool recording, button_pressed, led_on;
enum { AUDIO_CCC, BATT_CCC, ENCRYPTED };
static atomic_t flags;
static atomic_t adv_after_recycle;
static struct k_work adv_work;
static struct k_work_delayable window_work, led_work, button_work, security_work;

static struct bt_conn *connection_ref(void)
{
	k_spinlock_key_t key = k_spin_lock(&state_lock);
	struct bt_conn *conn = cur_conn ? bt_conn_ref(cur_conn) : NULL;
	k_spin_unlock(&state_lock, key);
	return conn;
}

static int64_t window_remaining(void)
{
	k_spinlock_key_t key = k_spin_lock(&state_lock);
	int64_t remaining = pairing_deadline - k_uptime_get();
	k_spin_unlock(&state_lock, key);
	return MAX(remaining, 0);
}

static void batt_ccc_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
	atomic_set_bit_to(&flags, BATT_CCC, value == BT_GATT_CCC_NOTIFY);
}

static ssize_t batt_read(struct bt_conn *conn, const struct bt_gatt_attr *attr,
			 void *buf, uint16_t len, uint16_t offset)
{
	uint16_t mv = sys_cpu_to_le16(battery_mv());
	return bt_gatt_attr_read(conn, attr, buf, len, offset, &mv, sizeof(mv));
}

static void ccc_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
	atomic_set_bit_to(&flags, AUDIO_CCC, value == BT_GATT_CCC_NOTIFY);
}

BT_GATT_SERVICE_DEFINE(rig_svc,
	BT_GATT_PRIMARY_SERVICE(&svc_uuid),
	BT_GATT_CHARACTERISTIC(&audio_uuid.uuid, BT_GATT_CHRC_NOTIFY,
			       BT_GATT_PERM_NONE, NULL, NULL, NULL),
	BT_GATT_CCC(ccc_changed, BT_GATT_PERM_READ_ENCRYPT | BT_GATT_PERM_WRITE_ENCRYPT),
	BT_GATT_CHARACTERISTIC(&batt_uuid.uuid, BT_GATT_CHRC_READ | BT_GATT_CHRC_NOTIFY,
			       BT_GATT_PERM_READ_ENCRYPT, batt_read, NULL, NULL),
	BT_GATT_CCC(batt_ccc_changed, BT_GATT_PERM_READ_ENCRYPT | BT_GATT_PERM_WRITE_ENCRYPT),
);

/* 128-bit service data：本服务 UUID + 版本/配对标记 01。
 * 普通广播 21 字节，窗口广播 22 字节，名称放 scan response，均在 31 字节内。 */
static const struct bt_data ad[] = {
	BT_DATA_BYTES(BT_DATA_FLAGS, (BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR)),
	BT_DATA_BYTES(BT_DATA_UUID128_ALL, RIG_UUID(1)),
};
static const struct bt_data pair_ad[] = {
	BT_DATA_BYTES(BT_DATA_FLAGS, (BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR)),
	BT_DATA_BYTES(BT_DATA_SVC_DATA128, RIG_UUID(1), 0x01),
};
static const struct bt_data sd[] = {
	BT_DATA(BT_DATA_NAME_COMPLETE, CONFIG_BT_DEVICE_NAME, sizeof(CONFIG_BT_DEVICE_NAME) - 1),
};

static void adv_start(struct k_work *w)
{
	/* 开关窗口时必须换掉广播里的标记。已连接时等 recycled 再广播。 */
	bt_le_adv_stop();
	struct bt_conn *conn = connection_ref();
	if (conn) {
		bt_conn_unref(conn);
		return;
	}
	bool pairing = window_remaining() > 0;
	const struct bt_data *data = pairing ? pair_ad : ad;
	size_t count = pairing ? ARRAY_SIZE(pair_ad) : ARRAY_SIZE(ad);
	int err = bt_le_adv_start(BT_LE_ADV_CONN_FAST_2, data, count, sd, ARRAY_SIZE(sd));
	if (err && err != -EALREADY) {
		printk("adv start failed: %d\n", err);
	}
}

static void led_update(struct k_work *w)
{
	bool pairing = window_remaining() > 0;
	k_spinlock_key_t key = k_spin_lock(&state_lock);
	bool active = recording;
	k_spin_unlock(&state_lock, key);
	led_on = pairing ? !led_on : active;
	gpio_pin_set_dt(&led, led_on);
	if (pairing) {
		k_work_reschedule(&led_work, K_MSEC(250));
	}
}

void ble_recording(bool active)
{
	k_spinlock_key_t key = k_spin_lock(&state_lock);
	recording = active;
	k_spin_unlock(&state_lock, key);
	if (window_remaining() == 0) {
		k_work_reschedule(&led_work, K_NO_WAIT);
	}
}

static void security_timeout(struct k_work *w)
{
	struct bt_conn *conn = connection_ref();
	if (!conn) {
		return;
	}
	k_spinlock_key_t key = k_spin_lock(&state_lock);
	int64_t remaining = security_deadline - k_uptime_get();
	k_spin_unlock(&state_lock, key);
	if (bt_conn_get_security(conn) < BT_SECURITY_L2) {
		if (remaining > 0) {
			k_work_reschedule(&security_work, K_MSEC(remaining));
		} else {
			bt_conn_disconnect(conn, BT_HCI_ERR_AUTH_FAIL);
		}
	}
	bt_conn_unref(conn);
}

static void window_expired(struct k_work *w)
{
	int64_t remaining = window_remaining();
	if (remaining > 0) {
		k_work_reschedule(&window_work, K_MSEC(remaining));
		return;
	}
	k_work_submit(&adv_work);
	k_work_reschedule(&led_work, K_NO_WAIT);
	k_work_reschedule(&security_work, K_NO_WAIT);
	printk("pairing window closed\n");
}

static void button_debounced(struct k_work *w)
{
	int value = gpio_pin_get_dt(&button);
	if (value < 0 || (value > 0) == button_pressed) {
		return;
	}
	button_pressed = value > 0;
	if (!button_pressed) {
		return;
	}
	k_spinlock_key_t key = k_spin_lock(&state_lock);
	pairing_deadline = k_uptime_get() + PAIR_WINDOW_MS;
	k_spin_unlock(&state_lock, key);
	k_work_reschedule(&window_work, K_MSEC(PAIR_WINDOW_MS));
	k_work_reschedule(&led_work, K_NO_WAIT);
	/* MAX_CONN=1：让新手机有机会连进来，误按不删除任何 bond。 */
	struct bt_conn *conn = connection_ref();
	if (conn) {
		bt_conn_disconnect(conn, BT_HCI_ERR_REMOTE_USER_TERM_CONN);
		bt_conn_unref(conn);
	}
	k_work_submit(&adv_work);
	printk("pairing window open (60s)\n");
}

static void button_changed(const struct device *dev, struct gpio_callback *cb, uint32_t pins)
{
	k_work_reschedule(&button_work, K_MSEC(DEBOUNCE_MS));
}

static enum bt_security_err pairing_accept(struct bt_conn *conn,
					 const struct bt_conn_pairing_feat *const feat)
{
	return window_remaining() > 0 ? BT_SECURITY_ERR_SUCCESS : BT_SECURITY_ERR_PAIR_NOT_ALLOWED;
}

struct old_bonds {
	const bt_addr_le_t *keep;
	bt_addr_le_t peers[CONFIG_BT_MAX_PAIRED];
	size_t count;
};

static void collect_old_bond(const struct bt_bond_info *info, void *data)
{
	struct old_bonds *old = data;
	if (!bt_addr_le_eq(&info->addr, old->keep) && old->count < ARRAY_SIZE(old->peers)) {
		bt_addr_le_copy(&old->peers[old->count++], &info->addr);
	}
}

static void pairing_complete(struct bt_conn *conn, bool bonded)
{
	if (!bonded) {
		bt_conn_disconnect(conn, BT_HCI_ERR_AUTH_FAIL);
		return;
	}
	/* Zephyr 已先持久化新密钥。先收集后删除，避免在遍历时改 bond 表。 */
	struct old_bonds old = { .keep = bt_conn_get_dst(conn) };
	bt_foreach_bond(BT_ID_DEFAULT, collect_old_bond, &old);
	for (size_t i = 0; i < old.count; i++) {
		int err = bt_unpair(BT_ID_DEFAULT, &old.peers[i]);
		if (err) {
			printk("old bond removal failed: %d\n", err);
		}
	}
	printk("pairing complete\n");
}

static void pairing_failed(struct bt_conn *conn, enum bt_security_err reason)
{
	bt_conn_disconnect(conn, BT_HCI_ERR_AUTH_FAIL);
}

static const struct bt_conn_auth_cb auth_cb = { .pairing_accept = pairing_accept };
static struct bt_conn_auth_info_cb auth_info_cb = {
	.pairing_complete = pairing_complete,
	.pairing_failed = pairing_failed,
};

static void connected(struct bt_conn *conn, uint8_t err)
{
	if (err) {
		printk("connect failed: %u\n", err);
		atomic_set(&adv_after_recycle, 1);
		return;
	}
	/* CCC 可能在 connected 回调之前已经从 settings 恢复，不能清掉它。 */
	atomic_clear_bit(&flags, ENCRYPTED);
	k_spinlock_key_t key = k_spin_lock(&state_lock);
	cur_conn = bt_conn_ref(conn);
	int64_t now = k_uptime_get();
	security_deadline = pairing_deadline > now ? pairing_deadline : now + UNENCRYPTED_MS;
	int64_t timeout = security_deadline - now;
	k_spin_unlock(&state_lock, key);
	k_work_reschedule(&security_work, K_MSEC(timeout));
	printk("connected\n");

	bt_conn_le_phy_update(conn, BT_CONN_LE_PHY_PARAM_2M);
	struct bt_conn_le_data_len_param dl = { .tx_max_len = 251, .tx_max_time = 2120 };
	bt_conn_le_data_len_update(conn, &dl);
	/* Android 在完成 bond 前不做 GATT 操作；已配对设备也需主动恢复加密。
	 * 无旧密钥时不在这里发起配对，由手机 createBond / 受保护的 GATT 请求触发。 */
	if (bt_le_bond_exists(BT_ID_DEFAULT, bt_conn_get_dst(conn))) {
		bt_conn_set_security(conn, BT_SECURITY_L2);
	}
}

static void security_changed(struct bt_conn *conn, bt_security_t level, enum bt_security_err err)
{
	atomic_set_bit_to(&flags, ENCRYPTED, !err && level >= BT_SECURITY_L2);
	if (err) {
		bt_conn_disconnect(conn, BT_HCI_ERR_AUTH_FAIL);
	} else if (level >= BT_SECURITY_L2) {
		k_work_cancel_delayable(&security_work);
	}
}

static void disconnected(struct bt_conn *conn, uint8_t reason)
{
	atomic_clear(&flags);
	atomic_set(&adv_after_recycle, 1);
	k_work_cancel_delayable(&security_work);
	k_spinlock_key_t key = k_spin_lock(&state_lock);
	struct bt_conn *old = cur_conn;
	cur_conn = NULL;
	k_spin_unlock(&state_lock, key);
	if (old) {
		bt_conn_unref(old);
	}
	printk("disconnected (reason %u)\n", reason);
}

static void recycled(void)
{
	/* bt_le_adv_stop 释放可连接广播的连接对象时也会走到这里。
	 * 只有真正断开过才重启广播，否则 adv_start 的停止动作会造成死循环。 */
	if (atomic_cas(&adv_after_recycle, 1, 0)) {
		k_work_submit(&adv_work);
	}
}

BT_CONN_CB_DEFINE(conn_cb) = {
	.connected = connected,
	.disconnected = disconnected,
	.recycled = recycled,
	.security_changed = security_changed,
};

static void mtu_updated(struct bt_conn *conn, uint16_t tx, uint16_t rx)
{
	printk("MTU: tx %u rx %u\n", tx, rx);
}

static struct bt_gatt_cb gatt_cb = { .att_mtu_updated = mtu_updated };

int ble_init(void)
{
	if (!gpio_is_ready_dt(&led) || !gpio_is_ready_dt(&button)) {
		return -ENODEV;
	}
	int err = gpio_pin_configure_dt(&led, GPIO_OUTPUT_INACTIVE);
	if (err) {
		return err;
	}
	k_work_init(&adv_work, adv_start);
	k_work_init_delayable(&window_work, window_expired);
	k_work_init_delayable(&led_work, led_update);
	k_work_init_delayable(&button_work, button_debounced);
	k_work_init_delayable(&security_work, security_timeout);
	err = bt_conn_auth_cb_register(&auth_cb);
	if (!err) {
		err = bt_conn_auth_info_cb_register(&auth_info_cb);
	}
	if (!err) {
		err = bt_enable(NULL);
	}
	if (!err) {
		err = settings_load();
	}
	if (err) {
		return err; /* settings 不可用时不广播，不能假装配对已经持久化 */
	}
	bt_gatt_cb_register(&gatt_cb);
	err = gpio_pin_configure_dt(&button, GPIO_INPUT);
	if (err) {
		return err;
	}
	button_pressed = gpio_pin_get_dt(&button) > 0;
	gpio_init_callback(&button_cb, button_changed, BIT(button.pin));
	err = gpio_add_callback(button.port, &button_cb);
	if (!err) {
		err = gpio_pin_interrupt_configure_dt(&button, GPIO_INT_EDGE_BOTH);
	}
	if (err) {
		return err;
	}
	k_work_submit(&adv_work);
	return 0;
}

bool ble_streaming(void)
{
	/* 包括从 settings 恢复 CCC 的情况：加密前绝不开麦。 */
	return atomic_test_bit(&flags, ENCRYPTED) && atomic_test_bit(&flags, AUDIO_CCC);
}

int ble_send_audio(uint16_t seq, const uint8_t *data, size_t len)
{
	uint8_t pkt[2 + OPUS_MAX_BYTES];
	struct bt_conn *conn = connection_ref();
	if (!conn) {
		return -ENOTCONN;
	}
	int err = -ENOTCONN;
	if (!ble_streaming() || len > OPUS_MAX_BYTES) {
		goto out;
	}
	if (len + 2 > bt_gatt_get_mtu(conn) - 3) {
		err = -EMSGSIZE;
		goto out;
	}
	sys_put_le16(seq, pkt);
	if (len) {
		memcpy(&pkt[2], data, len);
	}
	for (int i = 0; i < 20; i++) {
		err = bt_gatt_notify(conn, &rig_svc.attrs[1], pkt, len + 2);
		if (err != -ENOMEM && err != -ENOBUFS) {
			break;
		}
		k_msleep(5);
	}
out:
	bt_conn_unref(conn);
	return err;
}

int ble_send_battery(uint16_t mv)
{
	struct bt_conn *conn = connection_ref();
	if (!conn) {
		return -ENOTCONN;
	}
	int err = -ENOTCONN;
	if (atomic_test_bit(&flags, ENCRYPTED) && atomic_test_bit(&flags, BATT_CCC)) {
		uint16_t v = sys_cpu_to_le16(mv);
		err = bt_gatt_notify(conn, &rig_svc.attrs[4], &v, sizeof(v));
	}
	bt_conn_unref(conn);
	return err;
}
