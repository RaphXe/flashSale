import csv

def generate_user_csv(filename="users.csv", start=4, end=100000):
    """生成包含 userId 列的 CSV 文件，值从 start 到 end（包含）"""
    with open(filename, 'w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f)
        # 写入列头
        writer.writerow(["userId"])
        # 写入每行数据
        for user_id in range(start, end + 1):
            writer.writerow([user_id])
    print(f"文件已生成：{filename}，共 {end - start + 1} 条记录")

if __name__ == "__main__":
    generate_user_csv()